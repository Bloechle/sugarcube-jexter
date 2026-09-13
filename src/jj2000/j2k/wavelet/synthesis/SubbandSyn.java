/*
 * CVS identifier:
 *
 * $Id: SubbandSyn.java,v 1.25 2001/07/26 08:54:59 grosbois Exp $
 *
 * Class:                   SubbandSyn
 *
 * Description:             Element for a tree structure for a description
 *                          of subband for the synthesis side.
 *
 *
 *
 * COPYRIGHT:
 * 
 * 
 * Copyright (c) 1999/2000 JJ2000 Partners.
 * */
package jj2000.j2k.wavelet.synthesis;

import jj2000.j2k.wavelet.Subband;
import jj2000.j2k.wavelet.WaveletFilter;

/**
 * This class represents a subband in a tree structure that describes the subband decomposition for a wavelet transform, specifically for the syhthesis side.
 *
 * <p>The element can be either a node or a leaf of the tree. If it is a node then ther are 4 descendants (LL, HL, LH and HH). If it is a leaf there are no
 * descendants.</p>
 *
 * <p>The tree is bidirectional. Each element in the tree structure has a "parent", which is the subband from which the element was obtained by decomposition.
 * The only exception is the root element which has no parent (i.e.it's null), for obvious reasons.</p>
 *
 */
public class SubbandSyn extends Subband
{
  /**
   * The reference to the parent of this subband. It is null for the root element. It is null by default.
   */
  private SubbandSyn parent;
  /**
   * The reference to the LL subband resulting from the decomposition of this subband. It is null by default.
   */
  private SubbandSyn subb_LL;
  /**
   * The reference to the HL subband (horizontal high-pass) resulting from the decomposition of this subband. It is null by default.
   */
  private SubbandSyn subb_HL;
  /**
   * The reference to the LH subband (vertical high-pass) resulting from the decomposition of this subband. It is null by default.
     *
   */
  private SubbandSyn subb_LH;
  /**
   * The reference to the HH subband resulting from the decomposition of this subband. It is null by default.
   */
  private SubbandSyn subb_HH;
  /**
   * The horizontal analysis filter used to recompose this subband, from its childs. This is applicable to "node" elements only. The default value is null.
   */
  public SynWTFilter hFilter;
  /**
   * The vertical analysis filter used to decompose this subband, from its childs. This is applicable to "node" elements only. The default value is null.
   */
  public SynWTFilter vFilter;
  /**
   * The number of magnitude bits
   */
  public int magbits = 0;

  /**
   * Creates a SubbandSyn element with all the default values. The dimensions are (0,0) and the upper left corner is (0,0).
     *
   */
  public SubbandSyn()
  {
  }

  /**
   * Creates the top-level node and the entire subband tree, with the top-level dimensions, the number of decompositions, and the decomposition tree as
   * specified.
   *
   * <p>This constructor just calls the same constructor of the super class.</p>
   *
   * @param w The top-level width
   *
   * @param h The top-level height
   *
   * @param ulcx The horizontal coordinate of the upper-left corner with respect to the canvas origin, in the component grid.
   *
   * @param ulcy The vertical coordinate of the upper-left corner with respect to the canvas origin, in the component grid.
   *
   * @param lvls The number of levels (or LL decompositions) in the tree.
   *
   * @param hfilters The horizontal wavelet synthesis filters for each resolution level, starting at resolution level 0.
   *
   * @param vfilters The vertical wavelet synthesis filters for each resolution level, starting at resolution level 0.
   *
   * @see Subband#Subband(int,int,int,int,int, WaveletFilter[],WaveletFilter[])
     *
   */
  public SubbandSyn(int w, int h, int ulcx, int ulcy, int lvls,
    WaveletFilter hfilters[], WaveletFilter vfilters[])
  {
    super(w, h, ulcx, ulcy, lvls, hfilters, vfilters);
  }

  /**
   * Returns the parent of this subband. The parent of a subband is the subband from which this one was obtained by decomposition. The root element has no
   * parent subband (null).
   *
   * @return The parent subband, or null for the root one.
     *
   */
  public Subband getParent()
  {
    return parent;
  }

  /**
   * Returns the LL child subband of this subband.
   *
   * @return The LL child subband, or null if there are no childs.
     *
   */
  public Subband getLL()
  {
    return subb_LL;
  }

  /**
   * Returns the HL (horizontal high-pass) child subband of this subband.
   *
   * @return The HL child subband, or null if there are no childs.
     *
   */
  public Subband getHL()
  {
    return subb_HL;
  }

  /**
   * Returns the LH (vertical high-pass) child subband of this subband.
   *
   * @return The LH child subband, or null if there are no childs.
     *
   */
  public Subband getLH()
  {
    return subb_LH;
  }

  /**
   * Returns the HH child subband of this subband.
   *
   * @return The HH child subband, or null if there are no childs.
     *
   */
  public Subband getHH()
  {
    return subb_HH;
  }

  /**
   * Splits the current subband in its four subbands. It changes the status of this element (from a leaf to a node, and sets the filters), creates the childs
   * and initializes them. An IllegalArgumentException is thrown if this subband is not a leaf.
   *
   * <p>It uses the initChilds() method to initialize the childs.</p>
   *
   * @param hfilter The horizontal wavelet filter used to decompose this subband. It has to be a SynWTFilter object.
   *
   * @param vfilter The vertical wavelet filter used to decompose this subband. It has to be a SynWTFilter object.
   *
   * @return A reference to the LL leaf (subb_LL).
   *
   * @see Subband#initChilds
     *
   */
  protected Subband split(WaveletFilter hfilter, WaveletFilter vfilter)
  {
    // Test that this is a node
    if (isNode)
      throw new IllegalArgumentException();

    // Modify this element into a node and set the filters
    isNode = true;
    this.hFilter = (SynWTFilter) hfilter;
    this.vFilter = (SynWTFilter) vfilter;

    // Create childs
    subb_LL = new SubbandSyn();
    subb_LH = new SubbandSyn();
    subb_HL = new SubbandSyn();
    subb_HH = new SubbandSyn();

    // Assign parent
    subb_LL.parent = this;
    subb_HL.parent = this;
    subb_LH.parent = this;
    subb_HH.parent = this;

    // Initialize childs
    initChilds();

    // Return reference to LL subband
    return subb_LL;
  }

  /**
   * This function returns the horizontal wavelet filter relevant to this subband
   *
   * @return The horizontal wavelet filter
     *
   */
  public WaveletFilter getHorWFilter()
  {
    return hFilter;
  }

  /**
   * This function returns the vertical wavelet filter relevant to this subband
   *
   * @return The vertical wavelet filter
     *
   */
  public WaveletFilter getVerWFilter()
  {
    return hFilter;
  }
}
