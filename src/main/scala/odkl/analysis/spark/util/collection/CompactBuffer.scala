/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * Copy of package org.apache.spark.util.collection.CompactBuffer
 *
 * Modifications:
 * - Added @SerialVersionUID
 * - made public
 */
package odkl.analysis.spark.util.collection

import scala.math.Ordering
import scala.reflect.ClassTag

/**
 * An append-only buffer similar to ArrayBuffer, but more memory-efficient for small buffers.
 * ArrayBuffer always allocates an Object array to store the data, with 16 entries by default,
 * so it has about 80-100 bytes of overhead. In contrast, CompactBuffer can keep up to two
 * elements in fields of the main object, and only allocates an Array[AnyRef] if there are more
 * entries than that. This makes it more efficient for operations like groupBy where we expect
 * some keys to have very few elements.
 */
@SerialVersionUID(1L)
class CompactBuffer[T: ClassTag] extends Seq[T] with Serializable {
  // First two elements
  private var element0: T = _
  private var element1: T = _

  // Number of elements, including our two in the main object
  private var curSize = 0

  // Array for extra elements
  private [CompactBuffer] var otherElements: Array[T] = null

  def this(initialCapacity: Int) = {
    this()
    if (initialCapacity > 2) {
      otherElements = new Array[T](initialCapacity - 2)
    }
  }

  def apply(position: Int): T = {
    if (position < 0 || position >= curSize) {
      throw new IndexOutOfBoundsException
    }
    if (position == 0) {
      element0
    } else if (position == 1) {
      element1
    } else {
      otherElements(position - 2)
    }
  }

  private def update(position: Int, value: T): Unit = {
    if (position < 0 || position >= curSize) {
      throw new IndexOutOfBoundsException
    }
    if (position == 0) {
      element0 = value
    } else if (position == 1) {
      element1 = value
    } else {
      otherElements(position - 2) = value
    }
  }

  def += (value: T): CompactBuffer[T] = {
    val newIndex = curSize
    if (newIndex == 0) {
      element0 = value
      curSize = 1
    } else if (newIndex == 1) {
      element1 = value
      curSize = 2
    } else {
      growToSize(curSize + 1)
      otherElements(newIndex - 2) = value
    }
    this
  }

  def ++= (values: TraversableOnce[T]): CompactBuffer[T] = {
    values match {
      // Optimize merging of CompactBuffers, used in cogroup and groupByKey
      case compactBuf: CompactBuffer[T] =>
        val oldSize = curSize
        // Copy the other buffer's size and elements to local variables in case it is equal to us
        val itsSize = compactBuf.curSize
        val itsElements = compactBuf.otherElements
        growToSize(curSize + itsSize)
        if (itsSize == 1) {
          this(oldSize) = compactBuf.element0
        } else if (itsSize == 2) {
          this(oldSize) = compactBuf.element0
          this(oldSize + 1) = compactBuf.element1
        } else if (itsSize > 2) {
          this(oldSize) = compactBuf.element0
          this(oldSize + 1) = compactBuf.element1
          // At this point our size is also above 2, so just copy its array directly into ours.
          // Note that since we added two elements above, the index in this.otherElements that we
          // should copy to is oldSize.
          System.arraycopy(itsElements, 0, otherElements, oldSize, itsSize - 2)
        }

      case _ =>
        values.foreach(e => this += e)
    }
    this
  }

  override def length: Int = curSize

  override def size: Int = curSize

  override def iterator: Iterator[T] = new Iterator[T] {
    private var pos = 0
    override def hasNext: Boolean = pos < curSize
    override def next(): T = {
      if (!hasNext) {
        throw new NoSuchElementException
      }
      pos += 1
      apply(pos - 1)
    }
  }

  /** Increase our size to newSize and grow the backing array if needed. */
  private def growToSize(newSize: Int): Unit = {
    if (newSize < 0) {
      throw new UnsupportedOperationException("Can't grow buffer past Int.MaxValue elements")
    }
    val capacity = if (otherElements != null) otherElements.length + 2 else 2
    if (newSize > capacity) {
      var newArrayLen = 8
      while (newSize - 2 > newArrayLen) {
        newArrayLen *= 2
        if (newArrayLen == Int.MinValue) {
          // Prevent overflow if we double from 2^30 to 2^31, which will become Int.MinValue.
          // Note that we set the new array length to Int.MaxValue - 2 so that our capacity
          // calculation above still gives a positive integer.
          newArrayLen = Int.MaxValue - 2
        }
      }
      val newArray = new Array[T](newArrayLen)
      if (otherElements != null) {
        System.arraycopy(otherElements, 0, newArray, 0, otherElements.length)
      }
      otherElements = newArray
    }
    curSize = newSize
  }

  /**
   * More efficient implementation of [[scala.collection.SeqLike.sortBy]]
   *
   * @param f     f the transformation function mapping elements
   *              to some other domain `B`.
   * @param ord   the ordering assumed on domain `B`.
   * @tparam B    the target type of the transformation `f`, and the type where
   *              the ordering `ord` is defined.
   * @return      a [[CompactBuffer]] consisting of the elements of this [[CompactBuffer]]
   *               sorted according to the ordering where `x < y` if
   *              `ord.lt(f(x), f(y))`.
   */
  override def sortBy[B](f: T => B)(implicit ord: Ordering[B]): CompactBuffer[T] = sorted(ord on f)

  /**
   * More efficient implementation of [[scala.collection.SeqLike.sorted]]
   *
   * @param ord   the ordering to be used to compare elements.
   * @return      a [[CompactBuffer]] consisting of the elements of this [[CompactBuffer]]
   *              sorted according to the ordering `ord`.
   */
  override def sorted[B >: T](implicit ord: Ordering[B]): CompactBuffer[T] = {
    val len = this.size
    val arr = new Array[T](len)
    for (i <- indices) {
      arr(i) = this(i)
    }
    java.util.Arrays.sort(arr.asInstanceOf[Array[Object]], ord.asInstanceOf[Ordering[Object]])
    val res = new CompactBuffer[T](len)
    for (i <- arr.indices) {
      res += arr(i)
    }
    res
  }

  //  def sortBy
}

private[spark] object CompactBuffer {
  def apply[T: ClassTag](): CompactBuffer[T] = new CompactBuffer[T]

  def apply[T: ClassTag](value: T): CompactBuffer[T] = {
    val buf = new CompactBuffer[T]
    buf += value
  }
}
