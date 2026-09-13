/* 
 * CVS identifier:
 * 
 * $Id: BlkImgDataSrc.java,v 1.9 2001/01/24 14:58:12 grosbois Exp $
 * 
 * Class:                   BlkImgDataSrc
 * 
 * Description:             Defines methods to transfer image data in blocks.
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
 * This interface defines the methods to transfer image data in blocks, without following any particular order (random access). This interface does not define
 * the methods to access the image characteristics, such as width, height, number of components, tiles, etc., or to change the current tile. That is provided by
 * other interfaces such as ImgData.
 *
 * <P>This interface has the notion of a current tile. All data, coordinates and dimensions are always relative to the current tile. If there is only one tile
 * then it is equivalent as having no tiles.
 *
 * <P>A block of requested data may never cross tile boundaries. This should be enforced by the implementing class, or the source of image data.
 *
 * <P>This interface defines the methods that can be used to retrieve image data. Implementing classes need not buffer all the image data, they can ask their
 * source to load the data they need.
 *
 * @see ImgData
 *
 */
public interface BlkImgDataSrc extends ImgData
{
  /**
   * Returns the position of the fixed point in the specified component, or equivalently the number of fractional bits. This is the position of the least
   * significant integral (i.e. non-fractional) bit, which is equivalent to the number of fractional bits. For instance, for fixed-point values with 2
   * fractional bits, 2 is returned. For floating-point data this value does not apply and 0 should be returned. Position 0 is the position of the least
   * significant bit in the data.
   *
   * @param c The index of the component.
   *
   * @return The position of the fixed-point, which is the same as the number of fractional bits. For floating-point data 0 is returned.
     *
   */
  public int getFixedPoint(int c);

  /**
   * Returns, in the blk argument, a block of image data containing the specifed rectangular area, in the specified component. The data is returned, as a
   * reference to the internal data, if any, instead of as a copy, therefore the returned data should not be modified.
   *
   * <P>The rectangular area to return is specified by the 'ulx', 'uly', 'w' and 'h' members of the 'blk' argument, relative to the current tile. These members
   * are not modified by this method. The 'offset' and 'scanw' of the returned data can be arbitrary. See the 'DataBlk' class.
   *
   * <P>This method, in general, is more efficient than the 'getCompData()' method since it may not copy the data. However if the array of returned data is to
   * be modified by the caller then the other method is probably preferable.
   *
   * <P>If possible, the data in the returned 'DataBlk' should be the internal data itself, instead of a copy, in order to increase the data transfer
   * efficiency. However, this depends on the particular implementation (it may be more convenient to just return a copy of the data). This is the reason why
   * the returned data should not be modified.
   *
   * <P>If the data array in <tt>blk</tt> is <tt>null</tt>, then a new one is created if necessary. The implementation of this interface may choose to return
   * the same array or a new one, depending on what is more efficient. Therefore, the data array in <tt>blk</tt> prior to the method call should not be
   * considered to contain the returned data, a new array may have been created. Instead, get the array from <tt>blk</tt> after the method has returned.
   *
   * <P>The returned data may have its 'progressive' attribute set. In this case the returned data is only an approximation of the "final" data.
   *
   * @param blk Its coordinates and dimensions specify the area to return, relative to the current tile. Some fields in this object are modified to return the
   * data.
   *
   * @param c The index of the component from which to get the data.
   *
   * @return The requested DataBlk
   *
   * @see #getCompData
     *
   */
  public DataBlk getInternCompData(DataBlk blk, int c);

  /**
   * Returns, in the blk argument, a block of image data containing the specifed rectangular area, in the specified component. The data is returned, as a copy
   * of the internal data, therefore the returned data can be modified "in place".
   *
   * <P>The rectangular area to return is specified by the 'ulx', 'uly', 'w' and 'h' members of the 'blk' argument, relative to the current tile. These members
   * are not modified by this method. The 'offset' of the returned data is 0, and the 'scanw' is the same as the block's width. See the 'DataBlk' class.
   *
   * <P>This method, in general, is less efficient than the 'getInternCompData()' method since, in general, it copies the data. However if the array of returned
   * data is to be modified by the caller then this method is preferable.
   *
   * <P>If the data array in 'blk' is 'null', then a new one is created. If the data array is not 'null' then it is reused, and it must be large enough to
   * contain the block's data. Otherwise an 'ArrayStoreException' or an 'IndexOutOfBoundsException' is thrown by the Java system.
   *
   * <P>The returned data may have its 'progressive' attribute set. In this case the returned data is only an approximation of the "final" data.
   *
   * @param blk Its coordinates and dimensions specify the area to return, relative to the current tile. If it contains a non-null data array, then it must be
   * large enough. If it contains a null data array a new one is created. Some fields in this object are modified to return the data.
   *
   * @param c The index of the component from which to get the data.
   *
   * @see #getInternCompData
     *
   */
  public DataBlk getCompData(DataBlk blk, int c);
}
