/* 
 * CVS identifier:
 * 
 * $Id: CBlkWTDataSrcDec.java,v 1.19 2001/09/20 12:46:31 grosbois Exp $
 * 
 * Class:                   CBlkWTDataSrcDec
 * 
 * Description:             Interface that define methods for trasnfer of WT
 *                          data in a code-block basis (decoder side).
 * 
 * 
 * 
 * COPYRIGHT:
 * 
 * 
 * Copyright (c) 1999/2000 JJ2000 Partners.
 * */
package jj2000.j2k.wavelet.synthesis;

import jj2000.j2k.image.DataBlk;
import jj2000.j2k.wavelet.Subband;
import jj2000.j2k.wavelet.WaveletTransform;

/**
 * This abstract class defines methods to transfer wavelet data in a code-block by code-block basis, for the decoder side. In each call to 'getCodeBlock()' or
 * 'getInternCodeBlock()' a new code-block is returned. The code-blocks are returned in no specific order.
 *
 * <p>This class is the source of data, in general, for the inverse wavelet transforms. See the 'InverseWT' class.</p>
 *
 * @see InvWTData
 * @see WaveletTransform
 * @see jj2000.j2k.quantization.dequantizer.CBlkQuantDataSrcDec
 * @see InverseWT
 *
 */
public interface CBlkWTDataSrcDec extends InvWTData
{
  /**
   * Returns the number of bits, referred to as the "range bits", corresponding to the nominal range of the data in the specified component.
   *
   * <p>The returned value corresponds to the nominal dynamic range of the reconstructed image data, not of the wavelet coefficients themselves. This is because
   * different subbands have different gains and thus different nominal ranges. To have an idea of the nominal range in each subband the subband analysis gain
   * value from the subband tree structure, returned by the 'getSynSubbandTree()' method, can be used. See the 'Subband' class for more details.</p>
   *
   * <p>If this number is <i>b</b> then for unsigned data the nominal range is between 0 and 2^b-1, and for signed data it is between -2^(b-1) and
   * 2^(b-1)-1.</p>
   *
   * @param c The index of the component.
   *
   * @return The number of bits corresponding to the nominal range of the data.
   *
   * @see Subband
     *
   */
  public int getNomRangeBits(int c);

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
   * Returns the specified code-block in the current tile for the specified component, as a copy (see below).
   *
   * <p>The returned code-block may be progressive, which is indicated by the 'progressive' variable of the returned 'DataBlk' object. If a code-block is
   * progressive it means that in a later request to this method for the same code-block it is possible to retrieve data which is a better approximation, since
   * meanwhile more data to decode for the code-block could have been received. If the code-block is not progressive then later calls to this method for the
   * same code-block will return the exact same data values.</p>
   *
   * <p>The data returned by this method is always a copy of the internal data of this object, if any, and it can be modified "in place" without any problems
   * after being returned. The 'offset' of the returned data is 0, and the 'scanw' is the same as the code-block width. See the 'DataBlk' class.</p>
   *
   * @param c The component for which to return the next code-block.
   *
   * @param m The vertical index of the code-block to return, in the specified subband.
   *
   * @param n The horizontal index of the code-block to return, in the specified subband.
   *
   * @param sb The subband in which the code-block to return is.
   *
   * @param cblk If non-null this object will be used to return the new code-block. If null a new one will be allocated and returned. If the "data" array of the
   * object is non-null it will be reused, if possible, to return the data.
   *
   * @return The next code-block in the current tile for component 'n', or null if all code-blocks for the current tile have been returned.
   *
   * @see DataBlk
     *
   */
  public DataBlk getCodeBlock(int c, int m, int n, SubbandSyn sb, DataBlk cblk);

  /**
   * Returns the specified code-block in the current tile for the specified component (as a reference or copy).
   *
   * <p>The returned code-block may be progressive, which is indicated by the 'progressive' variable of the returned 'DataBlk' object. If a code-block is
   * progressive it means that in a later request to this method for the same code-block it is possible to retrieve data which is a better approximation, since
   * meanwhile more data to decode for the code-block could have been received. If the code-block is not progressive then later calls to this method for the
   * same code-block will return the exact same data values.</p>
   *
   * <p>The data returned by this method can be the data in the internal buffer of this object, if any, and thus can not be modified by the caller. The 'offset'
   * and 'scanw' of the returned data can be arbitrary. See the 'DataBlk' class.</p>
   *
   * @param c The component for which to return the next code-block.
   *
   * @param m The vertical index of the code-block to return, in the specified subband.
   *
   * @param n The horizontal index of the code-block to return, in the specified subband.
   *
   * @param sb The subband in which the code-block to return is.
   *
   * @param cblk If non-null this object will be used to return the new code-block. If null a new one will be allocated and returned. If the "data" array of the
   * object is non-null it will be reused, if possible, to return the data.
   *
   * @return The next code-block in the current tile for component 'n', or null if all code-blocks for the current tile have been returned.
   *
   * @see DataBlk
     *
   */
  public DataBlk getInternCodeBlock(int c, int m, int n, SubbandSyn sb,
    DataBlk cblk);
}
