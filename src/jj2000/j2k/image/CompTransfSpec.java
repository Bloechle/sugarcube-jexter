/* 
 * CVS identifier:
 * 
 * $Id: CompTransfSpec.java,v 1.18 2001/04/10 14:23:26 grosbois Exp $
 * 
 * Class:                   CompTransfSpec
 * 
 * Description:             Component Transformation specification
 * 
 * 
 * 
 * COPYRIGHT:
 * 
 * 
 * Copyright (c) 1999/2000 JJ2000 Partners.
 *  */
package jj2000.j2k.image;

import jj2000.j2k.ModuleSpec;
import jj2000.j2k.image.invcomptransf.InvCompTransf;

/**
 * This class extends the ModuleSpec class in order to hold tile specifications for multiple component transformation
 *
 * @see ModuleSpec
 *
 */
public class CompTransfSpec extends ModuleSpec
{
  /**
   * Constructs an empty 'CompTransfSpec' with the specified number of tiles and components. This constructor is called by the decoder. Note: The number of
   * component is here for symmetry purpose. It is useless since only tile specifications are meaningful.
   *
   * @param nt Number of tiles
   *
   * @param nc Number of components
   *
   * @param type the type of the specification module i.e. tile specific, component specific or both.
     *
   */
  public CompTransfSpec(int nt, int nc, byte type)
  {
    super(nt, nc, type);
  }

  /**
   * Check if component transformation is used in any of the tiles. This method must not be used by the encoder.
   *
   * @return True if a component transformation is used in at least on tile.
     *
   */
  public boolean isCompTransfUsed()
  {
    if (((Integer) def).intValue() != InvCompTransf.NONE)
      return true;

    if (tileDef != null)
      for (int t = nTiles - 1; t >= 0; t--)
        if (tileDef[t] != null
          && (((Integer) tileDef[t]).intValue() != InvCompTransf.NONE))
          return true;
    return false;
  }
}
