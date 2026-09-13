/* 
 * CVS identifier:
 * 
 * $Id: MaxShiftSpec.java,v 1.10 2000/11/27 15:00:45 grosbois Exp $
 * 
 * Class:                   MaxShiftSpec
 * 
 * Description:             Generic class for storing module specs
 * 
 *
 * 
 * COPYRIGHT:
 * 
 * 
 * Copyright (c) 1999/2000 JJ2000 Partners.
 */
package jj2000.j2k.roi;

import jj2000.j2k.ModuleSpec;

/**
 * This class contains the maxshift scaling value for each tile-component. The scaling values used are calculated in the ROIScaler class
 *
 */
public class MaxShiftSpec extends ModuleSpec
{
  /**
   * Constructs a 'ModuleSpec' object, initializing all the components and tiles to the 'SPEC_DEF' spec type, for the specified number of components and tiles.
   *
   * @param nt The number of tiles
   *
   * @param nc The number of components
   *
   * @param type the type of the specification module i.e. tile specific, component specific or both.
     *
   */
  public MaxShiftSpec(int nt, int nc, byte type)
  {
    super(nt, nc, type);
  }
}
