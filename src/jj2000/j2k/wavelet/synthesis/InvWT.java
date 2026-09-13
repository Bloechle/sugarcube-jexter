/* 
 * CVS identifier:
 * 
 * $Id:
 * 
 * Class:                   InvWT
 * 
 * Description:             The interface for implementations of a inverse
 *                          wavelet transform.
 * 
 * 
 * 
 * COPYRIGHT:
 * 
 * 
 * Copyright (c) 1999/2000 JJ2000 Partners.
 * */
package jj2000.j2k.wavelet.synthesis;

import jj2000.j2k.wavelet.WaveletTransform;

/**
 * This interface extends the WaveletTransform with the specifics of inverse wavelet transforms. Classes that implement inverse wavelet transfoms should
 * implement this interface.
 *
 * <p>This class does not define the methods to transfer data, just the specifics to inverse wavelet transform. Different data transfer methods are envisageable
 * for different transforms.</p>
 *
 */
public interface InvWT extends WaveletTransform
{
  /**
   * Sets the image reconstruction resolution level. A value of 0 means reconstruction of an image with the lowest resolution (dimension) available.
   *
   * <p>Note: Image resolution level indexes may differ from tile-component resolution index. They are indeed indexed starting from the lowest number of
   * decomposition levels of each component of each tile.</p>
   *
   * <p>Example: For an image (1 tile) with 2 components (component 0 having 2 decomposition levels and component 1 having 3 decomposition levels), the first
   * (tile-) component has 3 resolution levels and the second one has 4 resolution levels, whereas the image has only 3 resolution levels available.</p>
   *
   * @param rl The image resolution level.
   *
   * @return The vertical coordinate of the image origin in the canvas system, on the reference grid.
     *
   */
  public void setImgResLevel(int rl);
}
