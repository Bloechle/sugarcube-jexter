/*
 * CVS Identifier:
 *
 * $Id: DataBlkFloat.java,v 1.7 2001/10/09 12:52:01 grosbois Exp $
 *
 * Interface:           DataBlkFloat
 *
 * Description:         A float implementation of DataBlk
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
 * This is an implementation of the <tt>DataBlk</tt> interface for 32 bit floating point data (float).
 *
 * <p>The methods in this class are declared final, so that they can be inlined by inlining compilers.</p>
 *
 * @see DataBlk
 *
 */
public class DataBlkFloat extends DataBlk
{
  /**
   * The array where the data is stored
   */
  public float[] data;

  /**
   * Creates a DataBlkFloat with 0 dimensions and no data array (i.e. data is null).
     *
   */
  public DataBlkFloat()
  {
  }

  /**
   * Creates a DataBlkFloat with the specified dimensions and position. The data array is initialized to an array of size w*h.
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
  public DataBlkFloat(int ulx, int uly, int w, int h)
  {
    this.ulx = ulx;
    this.uly = uly;
    this.w = w;
    this.h = h;
    offset = 0;
    scanw = w;
    data = new float[w * h];
  }

  /**
   * Copy constructor. Creates a DataBlkFloat which is the copy of the DataBlkFloat given as paramter.
   *
   * @param DataBlkFloat the object to be copied.
     *
   */
  public DataBlkFloat(DataBlkFloat src)
  {
    this.ulx = src.ulx;
    this.uly = src.uly;
    this.w = src.w;
    this.h = src.h;
    this.offset = 0;
    this.scanw = this.w;
    this.data = new float[this.w * this.h];
    for (int i = 0; i < this.h; i++)
      System.arraycopy(src.data, i * src.scanw,
        this.data, i * this.scanw, this.w);
  }

  /**
   * Returns the identifier of this data type, <tt>TYPE_FLOAT</tt>, as defined in <tt>DataBlk</tt>.
   *
   * @return The type of data stored. Always <tt>DataBlk.TYPE_FLOAT</tt>
   *
   * @see DataBlk#TYPE_FLOAT
     *
   */
  public final int getDataType()
  {
    return TYPE_FLOAT;
  }

  /**
   * Returns the array containing the data, or null if there is no data array. The returned array is a float array.
   *
   * @return The array of data (a float[]) or null if there is no data.
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
  public final float[] getDataFloat()
  {
    return data;
  }

  /**
   * Sets the data array to the specified one. The provided array must be a float array, otherwise a ClassCastException is thrown. The size of the array is not
   * checked for consistency with the block's dimensions.
   *
   * @param arr The data array to use. Must be a float array.
     *
   */
  public final void setData(Object arr)
  {
    data = (float[]) arr;
  }

  /**
   * Sets the data array to the specified one. The size of the array is not checked for consistency with the block's dimensions.
   *
   * @param arr The data array to use.
     *
   */
  public final void setDataFloat(float[] arr)
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
