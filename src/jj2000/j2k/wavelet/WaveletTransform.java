/*
 * CVS identifier:
 *
 * $Id: WaveletTransform.java,v 1.18 2001/10/24 12:02:35 grosbois Exp $
 *
 * Class:                   WaveletTransform
 *
 * Description:             Interface that defines how a forward or
 *                          inverse wavelet transform should present
 *                          itself.
 *
 *
 *
 * COPYRIGHT:
 * 
 * 
 * Copyright (c) 1999/2000 JJ2000 Partners.
 * */
package jj2000.j2k.wavelet;

import jj2000.j2k.image.ImgData;

/**
 * This interface defines how a forward or inverse wavelet transform should present itself. As specified in the ImgData interface, from which this class
 * inherits, all operations are confined to the current tile, and all coordinates are relative to it.
 *
 * <p>The definition of the methods in this interface allows for different types of implementation, reversibility and levels of decompositions for each
 * component and each tile. An implementation of this interface does not need to support all this flexibility (e.g., it may provide the same implementation type
 * and decomposition levels for all tiles and components).</p>
 *
 */
public interface WaveletTransform extends ImgData
{
  /**
   * ID for line based implementations of wavelet transforms.
     *
   */
  public final static int WT_IMPL_LINE = 0;
  /**
   * ID for full-page based implementations of wavelet transforms. Full-page based implementations should be avoided since they require large amounts of memory.
     *
   */
  public final static int WT_IMPL_FULL = 2;

  /**
   * Returns the reversibility of the wavelet transform for the specified component and tile. A wavelet transform is reversible when it is suitable for lossless
   * and lossy-to-lossless compression.
   *
   * @param t The index of the tile.
   *
   * @param c The index of the component.
   *
   * @return true is the wavelet transform is reversible, false if not.
     *
   */
  public boolean isReversible(int t, int c);

  /**
   * Returns the implementation type of this wavelet transform (WT_IMPL_LINE or WT_IMPL_FRAME) for the specified component, in the current tile.
   *
   * @param c The index of the component.
   *
   * @return WT_IMPL_LINE or WT_IMPL_FULL for line, block or full-page based transforms.
     *
   */
  public int getImplementationType(int c);
}
