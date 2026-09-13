/*
 * CVS identifier:
 *
 * $Id: InverseWT.java,v 1.34 2001/10/09 12:52:55 grosbois Exp $
 *
 * Class:                   InverseWT
 *
 * Description:             This interface defines the specifics
 *                          of inverse wavelet transforms.
 *
 *
 *
 * COPYRIGHT:
 * 
 * Copyright (c) 1999/2000 JJ2000 Partners.
 * */
package jj2000.j2k.wavelet.synthesis;

import jj2000.j2k.decoder.DecoderSpecs;
import jj2000.j2k.image.BlkImgDataSrc;

/**
 * This abstract class extends the WaveletTransform one with the specifics of inverse wavelet transforms.
 *
 * <p>The image can be reconstructed at different resolution levels. This is controlled by the setResLevel() method. All the image, tile and component
 * dimensions are relative the the resolution level being used. The number of resolution levels indicates the number of wavelet recompositions that will be
 * used, if it is equal as the number of decomposition levels then the full resolution image is reconstructed.</p>
 *
 * <p>It is assumed in this class that all tiles and components the same reconstruction resolution level. If that where not the case the implementing class
 * should have additional data structures to store those values for each tile. However, the 'recResLvl' member variable always contain the values applicable to
 * the current tile, since many methods implemented here rely on them.</p>
 *
 */
public abstract class InverseWT extends InvWTAdapter implements BlkImgDataSrc
{
  /**
   * Initializes this object with the given source of wavelet coefficients. It initializes the resolution level for full resolutioin reconstruction (i.e. the
   * maximum resolution available from the 'src' source).
   *
   * <p>It is assumed here that all tiles and components have the same reconstruction resolution level. If that was not the case it should be the value for the
   * current tile of the source.</p>
   *
   * @param src from where the wavelet coefficinets should be obtained.
   *
   * @param decSpec The decoder specifications
     *
   */
  public InverseWT(MultiResImgData src, DecoderSpecs decSpec)
  {
    super(src, decSpec);
  }

  /**
   * Creates an InverseWT object that works on the data type of the source, with the special additional parameters from the parameter list. Currently the
   * parameter list is ignored since no special parameters can be specified for the inverse wavelet transform yet.
   *
   * @param src The source of data for the inverse wavelet transform.
   *
   * @param pl The parameter list containing parameters applicable to the inverse wavelet transform (other parameters can also be present).
     *
   */
  public static InverseWT createInstance(CBlkWTDataSrcDec src,
    DecoderSpecs decSpec)
  {

    // full page wavelet transform
    return new InvWTFull(src, decSpec);
  }
}
