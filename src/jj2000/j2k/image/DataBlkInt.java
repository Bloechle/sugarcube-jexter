/*
 * CVS Identifier:
 *
 * $Id: DataBlkInt.java,v 1.7 2001/10/09 12:51:54 grosbois Exp $
 *
 * Interface:           DataBlkInt
 *
 * Description:         A signed int implementation of DataBlk
 *
 *
 *
 * COPYRIGHT:
 * 
 * 
 * Copyright (c) 1999/2000 JJ2000 Partners.
 * */
package jj2000.j2k.image;

/**
 * This is an implementation of the <tt>DataBlk</tt> interface for signed 32 bit integral data.
 *
 * <p>The methods in this class are declared final, so that they can be inlined by inlining compilers.</p>
 *
 * @see DataBlk
 *
 */
public class DataBlkInt extends DataBlk
{
  /**
   * The array where the data is stored
   */
  public int[] data;

  /**
   * Creates a DataBlkInt with 0 dimensions and no data array (i.e. data is null).
     *
   */
  public DataBlkInt()
  {
  }

  /**
   * Creates a DataBlkInt with the specified dimensions and position. The data array is initialized to an array of size w*h.
   *
   * @param ulx The horizontal coordinate of the upper-left corner of the block
   *
   * @param uly The vertical coordinate of the upper-left corner of the block
   *
   * @param w The width of the block (in pixels)
   *
   * @param h The height of the block (in pixels)
     *
   */
  public DataBlkInt(int ulx, int uly, int w, int h)
  {
    this.ulx = ulx;
    this.uly = uly;
    this.w = w;
    this.h = h;
    offset = 0;
    scanw = w;
    data = new int[w * h];
  }

  /**
   * Copy constructor. Creates a DataBlkInt which is the copy of the DataBlkInt given as paramter.
   *
   * @param DataBlkInt the object to be copied.
     *
   */
  public DataBlkInt(DataBlkInt src)
  {
    this.ulx = src.ulx;
    this.uly = src.uly;
    this.w = src.w;
    this.h = src.h;
    this.offset = 0;
    this.scanw = this.w;
    this.data = new int[this.w * this.h];
    for (int i = 0; i < this.h; i++)
      System.arraycopy(src.data, i * src.scanw,
        this.data, i * this.scanw, this.w);
  }

  /**
   * Returns the identifier of this data type, <tt>TYPE_INT</tt>, as defined in <tt>DataBlk</tt>.
   *
   * @return The type of data stored. Always <tt>DataBlk.TYPE_INT</tt>
   *
   * @see DataBlk#TYPE_INT
     *
   */
  public final int getDataType()
  {
    return TYPE_INT;
  }

  /**
   * Returns the array containing the data, or null if there is no data array. The returned array is a int array.
   *
   * @return The array of data (a int[]) or null if there is no data.
     *
   */
  public final Object getData()
  {
    return data;
  }

  /**
   * Returns the array containing the data, or null if there is no data array.
   *
   * @return The array of data or null if there is no data.
     *
   */
  public final int[] getDataInt()
  {
    return data;
  }

  /**
   * Sets the data array to the specified one. The provided array must be a int array, otherwise a ClassCastException is thrown. The size of the array is not
   * checked for consistency with the block's dimensions.
   *
   * @param arr The data array to use. Must be a int array.
     *
   */
  public final void setData(Object arr)
  {
    data = (int[]) arr;
  }

  /**
   * Sets the data array to the specified one. The size of the array is not checked for consistency with the block's dimensions. This method is more efficient
   * than setData
   *
   * @param arr The data array to use.
     *
   */
  public final void setDataInt(int[] arr)
  {
    data = arr;
  }

  /**
   * Returns a string of informations about the DataBlkInt.
     *
   */
  public String toString()
  {
    String str = super.toString();
    if (data != null)
      str += ",data=" + data.length + " bytes";
    return str;
  }
}
