/*
 * CVS Identifier:
 *
 * $Id: DataBlk.java,v 1.7 2001/04/15 14:32:05 grosbois Exp $
 *
 * Interface:           DataBlk
 *
 * Description:         A generic interface to hold 2D blocks of data.
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
 * This is a generic abstract class to store data from a block of an image. This class does not have the notion of components. Therefore, it should be used for
 * data from a single component. Subclasses should implement the different types of storage (<tt>int</tt>, <tt>float</tt>, etc.).
 *
 * <p>The data is always stored in one array, of the type matching the data type (i.e. for 'int' it's an 'int[]'). The data should be stored in the array in
 * standard scan-line order. That is the samples go from the top-left corner of the code-block to the lower-right corner by line and then column.</p>
 *
 * <p>The member variable 'offset' gives the index in the array of the first data element (i.e. the top-left coefficient (ulx,uly)). The member variable 'scanw'
 * gives the width of the scan that is used to store the data, that can be different from the width of the block. Element '(x,y)' of the code-block (i.e.
 * '(ulx,uly)' is the top-left coefficient), will appear at position 'offset+(y-uly)*scanw+(x-ulx)' in the array of data.</p>
 *
 * <p>A block of data can have the <i>progressive</i> attribute set. Data is progressive when it is obtained by successive refinement and the values in this
 * block are approximations of the "final" values. When the final values are returned the progressive attribute must be turned off.</p>
 *
 * <p>The classes <tt>DataBlkInt</tt> and <tt>DataBlkFloat</tt> provide implementations for <tt>int</tt> and <tt>float</tt> types respectively.</p>
 *
 * @see DataBlkInt
 *
 * @see DataBlkFloat
 *
 */
public abstract class DataBlk
{
  /**
   * The identifier for the <tt>byte</tt> data type, as signed 8 bits.
   */
  public final static int TYPE_BYTE = 0;
  /**
   * The identifier for the <tt>short</tt> data type, as signed 16 bits.
   */
  public final static int TYPE_SHORT = 1;
  /**
   * The identifier for the <tt>int</tt> data type, as signed 32 bits.
   */
  public final static int TYPE_INT = 3;
  /**
   * The identifier for the <tt>float</tt> data type
   */
  public final static int TYPE_FLOAT = 4;
  /**
   * The horizontal coordinate (in pixels) of the upper-left corner of the block of data. This is relative to the component of the image from where this block
   * was filled or is to be filled.
   */
  public int ulx;
  /**
   * The vertical coordinate of the upper-left corner of the block of data. This is relative to the component of the image from where this block was filled or
   * is to be filled.
   */
  public int uly;
  /**
   * The width of the block, in pixels.
   */
  public int w;
  /**
   * The height of the block, in pixels.
   */
  public int h;
  /**
   * The offset in the array of the top-left coefficient
   */
  public int offset;
  /**
   * The width of the scanlines used to store the data in the array
   */
  public int scanw;
  /**
   * The progressive attribute (<tt>false</tt> by default)
   */
  public boolean progressive;

  /**
   * Returns the size in bits, given the data type. The data type must be one defined in this class. An <tt>IllegalArgumentException</tt> is thrown if
   * <tt>type</tt> is not defined in this class.
   *
   * @param type The data type.
   *
   * @return The size in bits of the data type.
     *
   */
  public static int getSize(int type)
  {
    switch (type)
    {
      case TYPE_BYTE:
        return 8;
      case TYPE_SHORT:
        return 16;
      case TYPE_INT:
      case TYPE_FLOAT:
        return 32;
      default:
        throw new IllegalArgumentException();
    }
  }

  /**
   * Returns the data type of the <tt>DataBlk</tt> object, as defined in this class.
   *
   * @return The data type of the object, as defined in thsi class.
     *
   */
  public abstract int getDataType();

  /**
   * Returns the array containing the data, or null if there is no data. The returned array is of the type returned by <tt>getDataType()</tt> (e.g., for
   * <tt>TYPE_INT</tt>, it is a <tt>int[]</tt>).
   *
   * <p>Each implementing class should provide a type specific equivalent method (e.g., <tt>getDataInt()</tt> in <tt>DataBlkInt</tt>) which returns an array of
   * the correct type explicetely and not through an <tt>Object</tt>.</p>
   *
   * @return The array containing the data, or <tt>null</tt> if there is no data.
   *
   * @see #getDataType
     *
   */
  public abstract Object getData();

  /**
   * Sets the data array to the specified one. The type of the specified data array must match the one returned by <tt>getDataType()</tt> (e.g., for
   * <tt>TYPE_INT</tt>, it should be a <tt>int[]</tt>). If the wrong type of array is given a <tt>ClassCastException</tt> will be thrown.
   *
   * <p>The size of the array is not necessarily checked for consistency with <tt>w</tt> and <tt>h</tt> or any other fields.</p>
   *
   * <p>Each implementing class should provide a type specific equivalent method (e.g., <tt>setDataInt()</tt> in <tt>DataBlkInt</tt>) which takes an array of
   * the correct type explicetely and not through an <tt>Object</tt>.</p>
   *
   * @param arr The new data array to use
   *
   * @see #getDataType
     *
   */
  public abstract void setData(Object arr);

  /**
   * Returns a string of informations about the DataBlk
   *
   * @return Block dimensions and progressiveness in a string
     *
   */
  public String toString()
  {
    String typeString = "";
    switch (getDataType())
    {
      case TYPE_BYTE:
        typeString = "Unsigned Byte";
        break;
      case TYPE_SHORT:
        typeString = "Short";
        break;
      case TYPE_INT:
        typeString = "Integer";
        break;
      case TYPE_FLOAT:
        typeString = "Float";
        break;
    }

    return "DataBlk: "
      + "upper-left(" + ulx + "," + uly + "), width=" + w
      + ", height=" + h + ", progressive=" + progressive
      + ", offset=" + offset + ", scanw=" + scanw
      + ", type=" + typeString;
  }
}
