/*
 * Copyright (c) 2023, NVIDIA CORPORATION.
 *
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

package com.nvidia.spark.rapids

import scala.collection.mutable.ArrayBuffer

import ai.rapids.cudf.ColumnVector
import com.nvidia.spark.rapids.Arm.{closeOnExcept, withResource}
import com.nvidia.spark.rapids.RapidsPluginImplicits._
import com.nvidia.spark.rapids.RmmRapidsRetryIterator.withRetry

import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.types.{StringType, StructType}
import org.apache.spark.sql.vectorized.ColumnarBatch

/**
 * Wrapper class that specifies how many rows to replicate
 * the partition value.
 */
case class PartitionRowData(rowValue: InternalRow, rowNum: Int)

/**
 * Class to wrap columnar batch and partition rows data and utility functions to merge them.
 *
 * @param inputBatch           Input ColumnarBatch.
 * @param partitionedRowsData  Array of (row counts, InternalRow) pairs. Each entry specifies how
 *                             many rows to replicate the partition value.
 * @param partitionSchema      Schema of the partitioned data.
 */
case class BatchWithPartitionData(
    inputBatch: SpillableColumnarBatch,
    partitionedRowsData: Array[PartitionRowData],
    partitionSchema: StructType) extends AutoCloseable {

  /**
   * Merges the partitioned data with the input ColumnarBatch.
   *
   * @return Merged ColumnarBatch.
   */
  def mergeWithPartitionData(): ColumnarBatch = {
    withResource(inputBatch.getColumnarBatch()) { columnarBatch =>
      if (partitionSchema.isEmpty) {
        columnarBatch // No partition columns, return the original batch.
      } else {
        val partitionColumns = buildPartitionColumns()
        val numRows = partitionColumns.head.getRowCount.toInt
        withResource(new ColumnarBatch(partitionColumns.toArray, numRows)) { partBatch =>
          assert(columnarBatch.numRows == partBatch.numRows)
          GpuColumnVector.combineColumns(columnarBatch, partBatch)
        }
      }
    }
  }

  /**
   * Builds GPU-based partition columns by mapping partitioned data to a sequence of
   * GpuColumnVectors.
   *
   * @return A sequence of GpuColumnVectors, one for each partitioned column in the schema.
   */
  private def buildPartitionColumns(): Seq[GpuColumnVector] = {
    closeOnExcept(new Array[GpuColumnVector](partitionSchema.length)) { partitionColumns =>
      for ((field, colIndex) <- partitionSchema.zipWithIndex) {
        val dataType = field.dataType
        // Create an array to hold the individual columns for each partition.
        val singlePartCols = partitionedRowsData.safeMap {
          case PartitionRowData(valueRow, rowNum) =>
            val singleValue = valueRow.get(colIndex, dataType)
            withResource(GpuScalar.from(singleValue, dataType)) { singleScalar =>
              // Create a column vector from the GPU scalar, associated with the row number.
              ColumnVector.fromScalar(singleScalar, rowNum)
            }
        }

        // Concatenate the individual columns into a single column vector.
        val concatenatedColumn = withResource(singlePartCols) { _ =>
          if (singlePartCols.length > 1) {
            ColumnVector.concatenate(singlePartCols: _*)
          } else {
            singlePartCols.head.incRefCount()
          }
        }
        partitionColumns(colIndex) = GpuColumnVector.from(concatenatedColumn, field.dataType)
      }
      partitionColumns
    }
  }

  override def close(): Unit = {
    inputBatch.close()
  }
}

/**
 * An iterator for merging and retrieving split ColumnarBatches with partition data using
 * retry framework.
 */
class BatchWithPartitionDataIterator(batchesWithPartitionData: Seq[BatchWithPartitionData])
  extends GpuColumnarBatchIterator(true) {
  private val retryIter = withRetry(batchesWithPartitionData.toIterator,
    BatchWithPartitionDataUtils.splitBatchInHalf) { attempt =>
    attempt.mergeWithPartitionData()
  }

  override def hasNext: Boolean = retryIter.hasNext

  override def next(): ColumnarBatch = {
    retryIter.next()
  }

  override def doClose(): Unit = {
    batchesWithPartitionData.foreach(_.close())
  }
}


object BatchWithPartitionDataUtils {
  private val CUDF_COLUMN_SIZE_LIMIT: Long = new RapidsConf(SQLConf.get).cudfColumnSizeLimit

  /**
   * Splits partition data (values and row counts) into smaller batches, ensuring that
   * size of batch is less than cuDF size limit. Then, it utilizes these smaller
   * partitioned batches to split the input batch and merges them to generate
   * an Iterator of split ColumnarBatches.
   *
   * Using an Iterator ensures that the actual merging does not happen until
   * the batch is required, thus avoiding GPU memory wastage.
   *
   * @param batch           Input batch, will be closed after the call returns
   * @param partitionValues Partition values collected from the batch
   * @param partitionRows   Row numbers collected from the batch, and it should have
   *                        the same size with "partitionValues"
   * @param partitionSchema Partition schema
   * @return a new columnar batch iterator with partition values
   */
  def addPartitionValuesToBatch(
      batch: ColumnarBatch,
      partitionRows: Array[Long],
      partitionValues: Array[InternalRow],
      partitionSchema: StructType): GpuColumnarBatchIterator = {
    if (partitionSchema.nonEmpty) {
      withResource(batch) { _ =>
        val partitionRowData = partitionValues.zip(partitionRows).map {
          case (rowValue, rowNum) => PartitionRowData(rowValue, rowNum.toInt)
        }
        val partitionedGroups = splitPartitionDataIntoGroups(partitionRowData,
          partitionSchema, CUDF_COLUMN_SIZE_LIMIT)
        val splitBatches = splitAndCombineBatchWithPartitionData(batch, partitionedGroups,
          partitionSchema)
        new BatchWithPartitionDataIterator(splitBatches)
      }
    } else {
      new SingleGpuColumnarBatchIterator(batch)
    }
  }

  /**
   * Adds a single set of partition values to all rows in a ColumnarBatch.
   * @return a new columnar batch iterator with partition values
   */
  def addSinglePartitionValueToBatch(
      batch: ColumnarBatch,
      partitionValues: InternalRow,
      partitionSchema: StructType): GpuColumnarBatchIterator = {
    addPartitionValuesToBatch(batch, Array(batch.numRows), Array(partitionValues), partitionSchema)
  }

  /**
   * Splits partitions into smaller batches, ensuring that the batch size
   * for each column does not exceed the specified limit.
   *
   * Data structures:
   *  - sizeOfBatch:   Array that stores the size of batches for each column.
   *  - currentBatch:  Array used to hold the rows and partition values of the current batch.
   *  - resultBatches: Array that stores the resulting batches after splitting.
   *
   * Algorithm:
   *  - Initialize `sizeOfBatch` and `resultBatches`.
   *  - Iterate through `partRows`:
   *    - Get rowsInPartition - This can either be rows from new partition or
   *      rows remaining to be processed if there was a split.
   *    - Calculate the maximum number of rows we can fit in current batch without exceeding limit.
   *    - if max rows that fit < rows in partition, we need to split:
   *      - Append entry (InternalRow, max rows that fit) to the current batch.
   *      - Append current batch to the result batches.
   *      - Reset variables.
   *    - If there was no split,
   *      - Append entry (InternalRow, rowsInPartition) to the current batch.
   *      - Update sizeOfBatch with size of partition for each column.
   *      - This implies all remaining rows can be added in current batch without exceeding limit.
   *
   * Example:
   * {{{
   * Input:
   *    partition rows:   [10, 40, 70, 10, 11]
   *    partition values: [ [abc, ab], [bc, ab], [abc, bc], [aa, cc], [ade, fd] ]
   *    limit:  300 bytes
   *
   * Result:
   * [
   *  [ (10, [abc, ab]), (40, [bc, ab]), (63, [abc, bc]) ],
   *  [ (7, [abc, bc]), (10, [aa, cc]), (11, [ade, fd]) ]
   * ]
   * }}}
   *
   * @return An array of batches, containing (row counts, partition values) pairs,
   *         such that each batch's size is less than batch size limit.
   */
  private def splitPartitionDataIntoGroups(
      partitionRowData: Array[PartitionRowData],
      partSchema: StructType,
      maxSizeOfBatch: Long): Array[Array[PartitionRowData]] = {
    val allowedMaxSizeOfBatch = Math.min(CUDF_COLUMN_SIZE_LIMIT, maxSizeOfBatch)
    val resultBatches = ArrayBuffer[Array[PartitionRowData]]()
    val currentBatch = ArrayBuffer[PartitionRowData]()
    val sizeOfBatch = Array.fill(partSchema.length)(0L)
    var partIndex = 0
    var splitOccurred = false
    var rowsInPartition = 0
    while (partIndex < partitionRowData.length) {
      // If there was no split, get next partition else process remaining rows in current partition.
      if (!splitOccurred) {
        rowsInPartition = partitionRowData(partIndex).rowNum
      }
      val valuesInPartition = partitionRowData(partIndex).rowValue
      // Calculate the maximum number of rows that can fit in current batch.
      val maxRows = calculateMaxRows(valuesInPartition, partSchema, sizeOfBatch,
        allowedMaxSizeOfBatch)
      // Splitting occurs if for any column, maximum rows we can fit is less than rows in partition.
      splitOccurred = maxRows.min < rowsInPartition
      if (splitOccurred) {
        currentBatch.append(PartitionRowData(valuesInPartition, maxRows.min))
        resultBatches.append(currentBatch.toArray)
        currentBatch.clear()
        java.util.Arrays.fill(sizeOfBatch, 0)
        rowsInPartition -= maxRows.min
      } else {
        // If there was no split, all rows can fit in current batch.
        currentBatch.append(PartitionRowData(valuesInPartition, rowsInPartition))
        val partitionSizes = calculatePartitionSizes(rowsInPartition, valuesInPartition, partSchema)
        sizeOfBatch.indices.foreach(i => sizeOfBatch(i) += partitionSizes(i))
        // Process next partition
        partIndex += 1
      }
    }
    // Add the last remaining batch to resultBatches
    if (currentBatch.nonEmpty) {
      resultBatches.append(currentBatch.toArray)
    }
    resultBatches.toArray
  }

  /**
   * Calculates the partition size for each column as 'size of single value * number of rows'
   */
  private def calculatePartitionSizes(rowNum: Int, values: InternalRow,
      partSchema: StructType): Seq[Long] = {
    partSchema.zipWithIndex.map {
      case (field, colIndex) if field.dataType == StringType =>
        // Assumption: Columns of other data type would not have fit already. Do nothing.
        val singleValue = values.getString(colIndex)
        val sizeOfSingleValue = singleValue.getBytes("UTF-8").length.toLong
        rowNum * sizeOfSingleValue
      case _ => 0L
     }
  }

  /**
   * For each column, calculate the maximum number of rows that can fit into a batch
   * without exceeding limit.
   */
  private def calculateMaxRows(valuesInPartition: InternalRow, partSchema: StructType,
      sizeOfBatch: Array[Long], maxSizeOfBatch: Long): Seq[Int] = {
    partSchema.zipWithIndex.map {
      case (field, colIndex) if field.dataType == StringType =>
        val singleValue = valuesInPartition.getString(colIndex)
        val sizeOfSingleValue = singleValue.getBytes("UTF-8").length.toLong
        val availableSpace = maxSizeOfBatch - sizeOfBatch(colIndex)
        // Calculated max row numbers that can fit.
        val maxRows = (availableSpace / sizeOfSingleValue).toInt
        maxRows
      case _ => 0
    }
  }


  /**
   * Splits the input ColumnarBatch into smaller batches, wraps these batches with partition
   * data, and returns an Iterator.
   *
   * @note Partition values are merged with the columnar batches lazily by the resulting Iterator
   *       to save GPU memory.
   *
   * @param batch                     Input ColumnarBatch.
   * @param listOfPartitionedRowsData Array of arrays where each entry contains 'list of partition
   *                                  values and number of times it is replicated'. See example in
   *                                  `splitPartitionDataIntoGroups()`.
   * @param partitionSchema           Schema of partition ColumnVectors.
   * @return An Iterator of combined ColumnarBatches.
   *
   * @see [[com.nvidia.spark.rapids.PartitionRowData]]
   */
  private def splitAndCombineBatchWithPartitionData(
      batch: ColumnarBatch,
      listOfPartitionedRowsData: Array[Array[PartitionRowData]],
      partitionSchema: StructType): Seq[BatchWithPartitionData] = {
    val splitIndices = calculateSplitIndices(listOfPartitionedRowsData)
    closeOnExcept(splitColumnarBatch(batch, splitIndices)) { splitColumnarBatches =>
      assert(splitColumnarBatches.length == listOfPartitionedRowsData.length)
      // Combine the split GPU ColumnVectors with partition ColumnVectors.
      val combinedBatches = splitColumnarBatches.zip(listOfPartitionedRowsData).map {
        case (spillableBatch, partitionedRowsData) =>
          BatchWithPartitionData(spillableBatch, partitionedRowsData, partitionSchema)
      }
      combinedBatches
    }
  }

  /**
   * Helper method to calculate split indices for dividing ColumnarBatch based on the row counts.
   *
   * Example,
   * {{{
   * Input - Array of arrays having (row counts, InternalRow) pairs:
   * [
   *  [ (10, [abc, ab]), (40, [bc, ab]), (63, [abc, bc]) ],
   *  [ (7, [abc, bc]), (10, [aa, cc]), (11, [ade, fd]) ],
   *  [ (4, [ac, gbc]), (7, [aa, tt]), (15, [ate, afd]) ]
   * ]
   *
   * Result -
   *  - Row counts for each batch: [ 10+40+30, 7+10+11, 4+7+15 ] = [ 80, 28, 26 ]
   *  - Split Indices: [ 80, 108 ]
   *  - ColumnarBatch with 134 rows will be split in three batches having
   *    `[ 80, 28, 26 ]` rows in each.
   *}}}
   *
   * @param listOfPartitionedRowsData Array of arrays where each entry contains 'list of partition
   *                                  values and number of times it is replicated'. See example in
   *                                  `splitPartitionDataIntoGroups()`.
   * @return A sequence of split indices.
   */
  private def calculateSplitIndices(
      listOfPartitionedRowsData: Array[Array[PartitionRowData]]): Seq[Int] = {
    // Calculate the row counts for each batch
    val rowCountsForEachBatch = listOfPartitionedRowsData.map(partitionData =>
      partitionData.map {
        case PartitionRowData(_, rowNum) => rowNum
      }.sum
    )
    // Calculate split indices using cumulative sum
    rowCountsForEachBatch.scanLeft(0)(_ + _).drop(1).dropRight(1)
  }

  /**
   * Split a ColumnarBatch into multiple ColumnarBatches based on indices.
   */
  private def splitColumnarBatch(input: ColumnarBatch,
      splitIndices: Seq[Int]): Seq[SpillableColumnarBatch] = {
    if (splitIndices.isEmpty) {
      Seq(SpillableColumnarBatch(GpuColumnVector.incRefCounts(input),
        SpillPriorities.ACTIVE_ON_DECK_PRIORITY))
    } else {
      val schema = GpuColumnVector.extractTypes(input)
      val splitInput = withResource(GpuColumnVector.from(input)) { table =>
        table.contiguousSplit(splitIndices: _*)
      }
      splitInput.safeMap { ct =>
        SpillableColumnarBatch(ct, schema, SpillPriorities.ACTIVE_BATCHING_PRIORITY)
      }
    }
  }

  /**
   * Splits an array of `PartitionRowData` into two halves of equal size.
   *
   * Example 1,
   * {{{
   * Input:
   *   partitionedRowsData: [ (1000, [ab, cd]), (2000, [def, add]) ]
   *   result row: 1500
   *
   * Result:
   *   [
   *      [ (1000, [ab, cd]), (500 [def, add]) ],
   *      [ (1500, [def, add]) ]
   *   ]
   * }}}
   *
   * Example 2,
   * {{{
   * Input:
   *   partitionedRowsData: [ (1000, [ab, cd]) ]
   *   targetSize: 500
   *
   * Result:
   *   [
   *      [ (500, [ab, cd]) ],
   *      [ (500, [ab, cd]) ]
   *   ]
   * }}}
   *
   * @note This function ensures that splitting is possible even in cases where there is a single
   * large partition.
   */
  private def splitPartitionDataInHalf(
      partitionedRowsData: Array[PartitionRowData]): Array[Array[PartitionRowData]] = {
    val totalRows = partitionedRowsData.map(_.rowNum).sum
    var remainingRows = totalRows / 2
    val leftHalf = ArrayBuffer[PartitionRowData]()
    val rightHalf = ArrayBuffer[PartitionRowData]()
    partitionedRowsData.foreach { partitionRow: PartitionRowData =>
      if (remainingRows > 0) {
        // Determine how many rows to add to the left partition
        val rowsToAddToLeft = Math.min(partitionRow.rowNum, remainingRows)
        leftHalf.append(PartitionRowData(partitionRow.rowValue, rowsToAddToLeft))
        remainingRows -= rowsToAddToLeft
        if (remainingRows <= 0) {
          // Add remaining rows to the right partition
          rightHalf.append(PartitionRowData(partitionRow.rowValue,
            partitionRow.rowNum - rowsToAddToLeft))
        }
      } else {
        rightHalf.append(partitionRow)
      }
    }
    Array(leftHalf.toArray, rightHalf.toArray)
  }

  /**
   * Splits a `BatchWithPartitionData` into multiple batches, each containing roughly half the data.
   * This function is used by the retry framework.
   */
  def splitBatchInHalf: BatchWithPartitionData => Seq[BatchWithPartitionData] = {
    (batchWithPartData: BatchWithPartitionData) => {
      closeOnExcept(batchWithPartData) { _ =>
        batchWithPartData match {
          case BatchWithPartitionData(inputBatch, partitionedRowsData, partitionSchema) =>
            // Split partition rows data in two halves
            val splitPartitionData = splitPartitionDataInHalf(partitionedRowsData)
            // Split the batch into two groups
            val splitBatches = withResource(inputBatch.getColumnarBatch()) { batch =>
              splitAndCombineBatchWithPartitionData(batch, splitPartitionData, partitionSchema)
            }
            // Assert that the total number of rows in split batches is equal to the original batch
            val totalRowsInSplitBatches = splitBatches.map(_.inputBatch.numRows()).sum
            assert(totalRowsInSplitBatches == batchWithPartData.inputBatch.numRows())
            splitBatches
        }
      }
    }
  }
}
