/*
 * Copyright 2014 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.agrona.concurrent.broadcast;

import uk.co.real_logic.agrona.BitUtil;
import uk.co.real_logic.agrona.DirectBuffer;
import uk.co.real_logic.agrona.UnsafeAccess;
import uk.co.real_logic.agrona.concurrent.AtomicBuffer;

import static uk.co.real_logic.agrona.concurrent.broadcast.BroadcastBufferDescriptor.*;
import static uk.co.real_logic.agrona.concurrent.broadcast.RecordDescriptor.*;

/**
 * Transmit messages via an underlying broadcast buffer to zero or more {@link BroadcastReceiver}s.
 *
 * <b>Note:</b> This class is not threadsafe. Only one transmitter is allowed per broadcast buffer.
 */
public class BroadcastTransmitter
{
    private final AtomicBuffer buffer;
    private final long capacity;
    private final long mask;
    private final long maxMsgLength;
    private final long tailIntentCountIndex;
    private final long tailCounterIndex;
    private final long latestCounterIndex;

    /**
     * Construct a new broadcast transmitter based on an underlying {@link uk.co.real_logic.agrona.concurrent.AtomicBuffer}.
     * The underlying buffer must a power of 2 in size plus sufficient space
     * for the {@link BroadcastBufferDescriptor#TRAILER_LENGTH}.
     *
     * @param buffer via which messages will be exchanged.
     * @throws IllegalStateException if the buffer capacity is not a power of 2
     *                               plus {@link BroadcastBufferDescriptor#TRAILER_LENGTH} in capacity.
     */
    public BroadcastTransmitter(final AtomicBuffer buffer)
    {
        this.buffer = buffer;
        this.capacity = buffer.capacity() - TRAILER_LENGTH;

        checkCapacity(capacity);
        buffer.verifyAlignment();

        this.mask = capacity - 1;
        this.maxMsgLength = calculateMaxMessageLength(capacity);
        this.tailIntentCountIndex = capacity + TAIL_INTENT_COUNTER_OFFSET;
        this.tailCounterIndex = capacity + TAIL_COUNTER_OFFSET;
        this.latestCounterIndex = capacity + LATEST_COUNTER_OFFSET;
    }

    /**
     * Get the capacity of the underlying broadcast buffer.
     *
     * @return the capacity of the underlying broadcast buffer.
     */
    public long capacity()
    {
        return capacity;
    }

    /**
     * Get the maximum message length that can be transmitted for a buffer.
     *
     * @return the maximum message length that can be transmitted for a buffer.
     */
    public long maxMsgLength()
    {
        return maxMsgLength;
    }

    /**
     * Transmit a message to {@link BroadcastReceiver}s via the broadcast buffer.
     *
     * @param msgTypeId type of the message to be transmitted.
     * @param srcBuffer containing the encoded message to be transmitted.
     * @param srcIndex srcIndex in the source buffer at which the encoded message begins.
     * @param length in bytes of the encoded message.
     * @throws IllegalArgumentException of the msgTypeId is not valid,
     *                                  or if the message length is greater than {@link #maxMsgLength()}.
     */
    public void transmit(final int msgTypeId, final DirectBuffer srcBuffer, final long srcIndex, final long length)
    {
        checkTypeId(msgTypeId);
        checkMessageLength(length);

        final AtomicBuffer buffer = this.buffer;
        long currentTail = buffer.getLong(tailCounterIndex);
        long recordOffset = currentTail & mask;
        final long recordLength = HEADER_LENGTH + length;
        final long recordLengthAligned = BitUtil.align(recordLength, RECORD_ALIGNMENT);
        final long newTail = currentTail + recordLengthAligned;

        final long toEndOfBuffer = capacity - recordOffset;
        if (toEndOfBuffer < recordLengthAligned)
        {
            signalTailIntent(buffer, newTail + toEndOfBuffer);
            insertPaddingRecord(buffer, recordOffset, toEndOfBuffer);

            currentTail += toEndOfBuffer;
            recordOffset = 0;
        }
        else
        {
            signalTailIntent(buffer, newTail);
        }

        buffer.putLong(lengthOffset(recordOffset), recordLength);
        buffer.putInt(typeOffset(recordOffset), msgTypeId);

        buffer.putBytes(msgOffset(recordOffset), srcBuffer, srcIndex, length);

        buffer.putLong(latestCounterIndex, currentTail);
        buffer.putLongOrdered(tailCounterIndex, currentTail + recordLengthAligned);
    }

    private void signalTailIntent(final AtomicBuffer buffer, final long newTail)
    {
        buffer.putLong(tailIntentCountIndex, newTail);
        UnsafeAccess.UNSAFE.storeFence();
    }

    private static void insertPaddingRecord(final AtomicBuffer buffer, final long recordOffset, final long length)
    {
        buffer.putLong(lengthOffset(recordOffset), length);
        buffer.putInt(typeOffset(recordOffset), PADDING_MSG_TYPE_ID);
    }

    private void checkMessageLength(final long length)
    {
        if (length > maxMsgLength)
        {
            final String msg = String.format("encoded message exceeds maxMsgLength of %d, length=%d", maxMsgLength, length);

            throw new IllegalArgumentException(msg);
        }
    }
}
