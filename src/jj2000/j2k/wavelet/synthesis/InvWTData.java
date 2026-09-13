/* 
 * CVS identifier:
 * 
 * $Id: InvWTData.java,v 1.15 2001/09/20 13:07:09 grosbois Exp $
 * 
 * Class:                   InvWTData
 * 
 * Description:             <short description of class>
 * 
 * 
 * 
 * COPYRIGHT:
 * 
 * 
 * Copyright (c) 1999/2000 JJ2000 Partners.
 * */
package jj2000.j2k.wavelet.synthesis;

/**
 * This interface extends the MultiResImgData interface with the methods that are necessary for inverse wavelet data (i.e. data that is the source to an inverse
 * wavlet trasnform).
 *
 */
public interface InvWTData extends MultiResImgData
{
  /**
   * Returns the subband tree, for the specified tile-component. This method returns the root element of the subband tree structure, see 'Subband' and
   * 'SubbandSyn'. The tree comprises all the available resolution levels.
   *
   * @param t The index of the tile, from 0 to T-1.
   *
   * @param c The index of the component, from 0 to C-1.
   *
   * @return The root of the tree structure.
     *
   */
  public SubbandSyn getSynSubbandTree(int t, int c);

  /**
   * Returns the horizontal code-block partition origin. Allowable values are 0 and 1, nothing else.
     *
   */
  public int getCbULX();

  /**
   * Returns the vertical code-block partition origin Allowable values are 0 and 1, nothing else.
     *
   */
  public int getCbULY();
}
