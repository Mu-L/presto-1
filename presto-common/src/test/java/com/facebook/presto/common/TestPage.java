/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.common;

import com.facebook.presto.common.block.Block;
import com.facebook.presto.common.block.BlockBuilder;
import com.facebook.presto.common.block.DictionaryBlock;
import com.facebook.presto.common.block.DictionaryId;
import com.facebook.presto.common.block.VariableWidthBlock;
import io.airlift.slice.DynamicSliceOutput;
import io.airlift.slice.Slice;
import org.testng.annotations.Test;

import java.util.UUID;
import java.util.stream.LongStream;

import static com.facebook.presto.common.block.DictionaryId.randomDictionaryId;
import static com.facebook.presto.common.type.BigintType.BIGINT;
import static com.facebook.presto.common.type.VarbinaryType.VARBINARY;
import static com.facebook.presto.common.type.VarcharType.VARCHAR;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Verify.verify;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

public class TestPage
{
    @Test
    public void testGetRegion()
    {
        Page page = new Page(10);
        Page region = page.getRegion(0, 10);
        assertEquals(page.getRegion(5, 5).getPositionCount(), 5);
        assertEquals(region.getPositionCount(), 10);
        assertSame(page, region);
    }

    @Test
    public void testGetEmptyRegion()
    {
        Page page = new Page(10);
        assertEquals(new Page(0).getRegion(0, 0).getPositionCount(), 0);
        assertEquals(page.getRegion(5, 0).getPositionCount(), 0);
    }

    @Test(expectedExceptions = IndexOutOfBoundsException.class, expectedExceptionsMessageRegExp = "Invalid position 1 and length 1 in page with 0 positions")
    public void testGetRegionExceptions()
    {
        new Page(0).getRegion(1, 1);
    }

    @Test
    public void testGetRegionFromNoColumnPage()
    {
        assertEquals(new Page(100).getRegion(0, 10).getPositionCount(), 10);
    }

    @Test
    public void testSizesForNoColumnPage()
    {
        Page page = new Page(100);
        assertEquals(page.getSizeInBytes(), 0);
        assertEquals(page.getLogicalSizeInBytes(), 0);
        assertEquals(page.getRetainedSizeInBytes(), Page.INSTANCE_SIZE); // does not include the blocks[] array
    }

    @Test
    public void testCompactDictionaryBlocks()
    {
        int positionCount = 100;

        // Create 2 dictionary blocks with the same source id
        DictionaryId commonSourceId = randomDictionaryId();
        int commonDictionaryUsedPositions = 20;
        int[] commonDictionaryIds = getDictionaryIds(positionCount, commonDictionaryUsedPositions);

        // first dictionary contains "varbinary" values
        Slice[] dictionaryValues1 = createExpectedValues(50);
        Block dictionary1 = createSlicesBlock(dictionaryValues1);
        DictionaryBlock commonSourceIdBlock1 = new DictionaryBlock(positionCount, dictionary1, commonDictionaryIds, commonSourceId);

        // second dictionary block is "length(firstColumn)"
        BlockBuilder dictionary2 = BIGINT.createBlockBuilder(null, dictionary1.getPositionCount());
        for (Slice expectedValue : dictionaryValues1) {
            BIGINT.writeLong(dictionary2, expectedValue.length());
        }
        DictionaryBlock commonSourceIdBlock2 = new DictionaryBlock(positionCount, dictionary2.build(), commonDictionaryIds, commonSourceId);

        // Create block with a different source id, dictionary size, used
        int otherDictionaryUsedPositions = 30;
        int[] otherDictionaryIds = getDictionaryIds(positionCount, otherDictionaryUsedPositions);
        Block dictionary3 = createSlicesBlock(createExpectedValues(70));
        DictionaryBlock randomSourceIdBlock = new DictionaryBlock(dictionary3, otherDictionaryIds);

        Page page = new Page(commonSourceIdBlock1, randomSourceIdBlock, commonSourceIdBlock2);
        page.compact();

        // dictionary blocks should all be compact
        assertTrue(((DictionaryBlock) page.getBlock(0)).isCompact());
        assertTrue(((DictionaryBlock) page.getBlock(1)).isCompact());
        assertTrue(((DictionaryBlock) page.getBlock(2)).isCompact());
        assertEquals(((DictionaryBlock) page.getBlock(0)).getDictionary().getPositionCount(), commonDictionaryUsedPositions);
        assertEquals(((DictionaryBlock) page.getBlock(1)).getDictionary().getPositionCount(), otherDictionaryUsedPositions);
        assertEquals(((DictionaryBlock) page.getBlock(2)).getDictionary().getPositionCount(), commonDictionaryUsedPositions);

        // Blocks that had the same source id before compacting page should have the same source id after compacting page
        assertNotEquals(((DictionaryBlock) page.getBlock(0)).getDictionarySourceId(), ((DictionaryBlock) page.getBlock(1)).getDictionarySourceId());
        assertEquals(((DictionaryBlock) page.getBlock(0)).getDictionarySourceId(), ((DictionaryBlock) page.getBlock(2)).getDictionarySourceId());
    }

    @Test
    public void testGetPositions()
    {
        int entries = 10;
        BlockBuilder blockBuilder = BIGINT.createBlockBuilder(null, entries);
        for (int i = 0; i < entries; i++) {
            BIGINT.writeLong(blockBuilder, i);
        }
        Block block = blockBuilder.build();

        Page page = new Page(block, block, block).getPositions(new int[] {0, 1, 1, 1, 2, 5, 5}, 1, 5);
        assertEquals(page.getPositionCount(), 5);
        for (int i = 0; i < 3; i++) {
            assertEquals(page.getBlock(i).getLong(0), 1);
            assertEquals(page.getBlock(i).getLong(1), 1);
            assertEquals(page.getBlock(i).getLong(2), 1);
            assertEquals(page.getBlock(i).getLong(3), 2);
            assertEquals(page.getBlock(i).getLong(4), 5);
        }
    }

    @Test
    public void testDropColumn()
    {
        int entries = 10;
        BlockBuilder blockBuilder = BIGINT.createBlockBuilder(null, entries);
        for (int i = 0; i < entries; i++) {
            BIGINT.writeLong(blockBuilder, i);
        }
        Block block = blockBuilder.build();

        Page page = new Page(block, block, block);
        assertEquals(page.getChannelCount(), 3);
        Page newPage = page.dropColumn(1);
        assertEquals(page.getChannelCount(), 3, "Page was modified");
        assertEquals(newPage.getChannelCount(), 2);

        assertEquals(newPage.getBlock(0).getLong(0), 0);
        assertEquals(newPage.getBlock(1).getLong(1), 1);
    }

    @Test
    public void testReplaceColumn()
    {
        int entries = 10;
        BlockBuilder blockBuilder = BIGINT.createBlockBuilder(null, entries);
        for (int i = 0; i < entries; i++) {
            BIGINT.writeLong(blockBuilder, i);
        }
        Block block = blockBuilder.build();
        Page page = new Page(block, block, block);
        assertEquals(page.getBlock(1).getLong(0), 0);

        BlockBuilder newBlockBuilder = BIGINT.createBlockBuilder(null, entries);
        for (int i = 0; i < entries; i++) {
            BIGINT.writeLong(newBlockBuilder, -i);
        }
        Block newBlock = newBlockBuilder.build();
        Page newPage = page.replaceColumn(1, newBlock);

        assertEquals(newPage.getChannelCount(), 3);
        assertEquals(newPage.getPositionCount(), entries);
        assertEquals(newPage.getBlock(1).getLong(0), 0);
        assertEquals(newPage.getBlock(1).getLong(1), -1);
    }

    @Test(expectedExceptions = IndexOutOfBoundsException.class)
    public void testReplaceColumn_channelTooLow()
    {
        int entries = 10;
        BlockBuilder blockBuilder = BIGINT.createBlockBuilder(null, entries);
        for (int i = 0; i < entries; i++) {
            BIGINT.writeLong(blockBuilder, i);
        }
        Block block = blockBuilder.build();
        Page page = new Page(block, block, block);
        assertEquals(page.getBlock(1).getLong(0), 0);

        BlockBuilder newBlockBuilder = BIGINT.createBlockBuilder(null, entries);
        for (int i = 0; i < entries; i++) {
            BIGINT.writeLong(newBlockBuilder, -i);
        }
        Block newBlock = newBlockBuilder.build();
        page.replaceColumn(-1, newBlock);
    }

    @Test(expectedExceptions = IndexOutOfBoundsException.class)
    public void testReplaceColumn_channelTooHigh()
    {
        int entries = 10;
        BlockBuilder blockBuilder = BIGINT.createBlockBuilder(null, entries);
        for (int i = 0; i < entries; i++) {
            BIGINT.writeLong(blockBuilder, i);
        }
        Block block = blockBuilder.build();
        Page page = new Page(block, block, block);
        assertEquals(page.getBlock(1).getLong(0), 0);

        BlockBuilder newBlockBuilder = BIGINT.createBlockBuilder(null, entries);
        for (int i = 0; i < entries; i++) {
            BIGINT.writeLong(newBlockBuilder, -i);
        }
        Block newBlock = newBlockBuilder.build();
        page.replaceColumn(page.getChannelCount(), newBlock);
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void testReplaceColumn_WrongNumberOfRows()
    {
        int entries = 10;
        BlockBuilder blockBuilder = BIGINT.createBlockBuilder(null, entries);
        for (int i = 0; i < entries; i++) {
            BIGINT.writeLong(blockBuilder, i);
        }
        Block block = blockBuilder.build();
        Page page = new Page(block, block, block);
        assertEquals(page.getBlock(1).getLong(0), 0);

        BlockBuilder newBlockBuilder = BIGINT.createBlockBuilder(null, entries);
        for (int i = 0; i < entries - 5; i++) {
            BIGINT.writeLong(newBlockBuilder, -i);
        }
        Block newBlock = newBlockBuilder.build();
        page.replaceColumn(1, newBlock);
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void testPrependColumnWrongNumberOfRows()
    {
        int entries = 10;
        BlockBuilder blockBuilder = BIGINT.createBlockBuilder(null, entries);
        for (int i = 0; i < entries; i++) {
            BIGINT.writeLong(blockBuilder, i);
        }
        Block block = blockBuilder.build();
        Page page = new Page(block, block);

        BlockBuilder newBlockBuilder = BIGINT.createBlockBuilder(null, entries - 5);
        for (int i = 0; i < entries - 5; i++) {
            BIGINT.writeLong(newBlockBuilder, -i);
        }
        Block newBlock = newBlockBuilder.build();

        page.prependColumn(newBlock);
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void testAppendColumnsWrongNumberOfRows()
    {
        int entries = 10;
        BlockBuilder blockBuilder = BIGINT.createBlockBuilder(null, entries);
        for (int i = 0; i < entries; i++) {
            BIGINT.writeLong(blockBuilder, i);
        }
        Block block = blockBuilder.build();
        Page page = new Page(block, block);

        BlockBuilder newBlockBuilder = BIGINT.createBlockBuilder(null, entries - 5);
        for (int i = 0; i < entries - 5; i++) {
            BIGINT.writeLong(newBlockBuilder, -i);
        }
        Block newBlock = newBlockBuilder.build();

        page.appendColumn(newBlock);
    }

    @Test
    public void testRetainedSizeIsCorrect()
    {
        BlockBuilder variableWidthBlockBuilder = VARCHAR.createBlockBuilder(null, 256);

        LongStream.range(0, 100).forEach(value -> VARCHAR.writeString(variableWidthBlockBuilder, UUID.randomUUID().toString()));
        VariableWidthBlock variableWidthBlock = (VariableWidthBlock) variableWidthBlockBuilder.build();
        Page page = new Page(
                variableWidthBlock, // Original block
                variableWidthBlock, // Same block twice
                variableWidthBlock.getRegion(0, 50), // Block with same underlying slice
                variableWidthBlockBuilder.getRegion(51, 25)); // Block with slice having same underlying base object/byte array
        // Account for extra overhead of objects to be around 20%.
        // Close attention should be paid when this needs to be updated to 2x or higher as that case may introduce double counting
        double expectedMaximumSizeOfPage = variableWidthBlock.getRawSlice(0).getRetainedSize() * 1.2;
        assertTrue(page.getRetainedSizeInBytes() < expectedMaximumSizeOfPage, "Expected slice & underlying object to be counted once");
    }

    private static Slice[] createExpectedValues(int positionCount)
    {
        Slice[] expectedValues = new Slice[positionCount];
        for (int position = 0; position < positionCount; position++) {
            expectedValues[position] = createExpectedValue(position);
        }
        return expectedValues;
    }

    private static Slice createExpectedValue(int length)
    {
        DynamicSliceOutput dynamicSliceOutput = new DynamicSliceOutput(16);
        for (int index = 0; index < length; index++) {
            dynamicSliceOutput.writeByte(length * (index + 1));
        }
        return dynamicSliceOutput.slice();
    }

    private static int[] getDictionaryIds(int positionCount, int dictionarySize)
    {
        checkArgument(positionCount > dictionarySize);
        int[] ids = new int[positionCount];
        for (int i = 0; i < positionCount; i++) {
            ids[i] = i % dictionarySize;
        }
        return ids;
    }

    private static Block createSlicesBlock(Slice[] values)
    {
        BlockBuilder builder = VARBINARY.createBlockBuilder(null, 100);

        for (Slice value : values) {
            verify(value != null);
            VARBINARY.writeSlice(builder, value);
        }
        return builder.build();
    }
}
