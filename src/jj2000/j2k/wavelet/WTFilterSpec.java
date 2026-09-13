/* 
 * CVS identifier:
 * 
 * $Id: WTFilterSpec.java,v 1.10 2000/09/05 09:26:08 grosbois Exp $
 * 
 * Class:                   WTFilterSpec
 * 
 * Description:             Generic class for storing wavelet filter specs
 * 
 * 
 * 
 * COPYRIGHT:
 * 
 * 
 * Copyright (c) 1999/2000 JJ2000 Partners.
 * 
 * 
 * 
 */
package jj2000.j2k.wavelet;

/**
 * This is the generic class from which the ones that hold the analysis or synthesis filters to be used in each part of the image derive. See AnWTFilterSpec and
 * SynWTFilterSpec.
 *
 * <P>The filters to use are defined by a hierarchy. The hierarchy is:
 *
 * <P>- Tile and component specific filters<br> - Tile specific default filters<br> - Component main default filters<br> - Main default filters<br>
 *
 * <P>At the moment tiles are not supported by this class.
 *
 * @see jj2000.j2k.wavelet.analysis.AnWTFilterSpec
 *
 * @see jj2000.j2k.wavelet.synthesis.SynWTFilterSpec
 *
 */
public abstract class WTFilterSpec
{
  /**
   * The identifier for "main default" specified filters
   */
  public final static byte FILTER_SPEC_MAIN_DEF = 0;
  /**
   * The identifier for "component default" specified filters
   */
  public final static byte FILTER_SPEC_COMP_DEF = 1;
  /**
   * The identifier for "tile specific default" specified filters
   */
  public final static byte FILTER_SPEC_TILE_DEF = 2;
  /**
   * The identifier for "tile and component specific" specified filters
   */
  public final static byte FILTER_SPEC_TILE_COMP = 3;
  /**
   * The spec type for each tile and component. The first index is the component index, the second is the tile index. NOTE: The tile specific things are not
   * supported yet.
   */
  // Use byte to save memory (no need for speed here).
  protected byte specValType[];

  /**
   * Constructs a 'WTFilterSpec' object, initializing all the components and tiles to the 'FILTER_SPEC_MAIN_DEF' spec type, for the specified number of
   * components and tiles.
   *
   * <P>NOTE: The tile specific things are not supported yet
   *
   * @param nc The number of components
   *
   * @param nt The number of tiles
   *
   *
   *
   */
  protected WTFilterSpec(int nc)
  {
    specValType = new byte[nc];
  }

  /**
   * Returns the data type used by the filters in this object, as defined in the 'DataBlk' interface.
   *
   * @return The data type of the filters in this object
   *
   * @see jj2000.j2k.image.DataBlk
   *
   *
   *
   */
  public abstract int getWTDataType();

  /**
   * Returns the type of specification for the filters in the specified component and tile. The specification type is one of: 'FILTER_SPEC_MAIN_DEF',
   * 'FILTER_SPEC_COMP_DEF', 'FILTER_SPEC_TILE_DEF', 'FILTER_SPEC_TILE_COMP'.
   *
   * <P>NOTE: The tile specific things are not supported yet
   *
   * @param n The component index
   *
   * @param t The tile index, in raster scan order.
   *
   * @return The specification type for component 'n' and tile 't'.
   *
   *
   *
   */
  public byte getKerSpecType(int n)
  {
    return specValType[n];
  }
}
