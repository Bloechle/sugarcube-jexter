/*
 * CVS identifier:
 *
 * $Id: FilterTypes.java,v 1.12 2001/05/08 16:14:28 grosbois Exp $
 *
 * Class:                   FilterTypes
 *
 * Description:             Defines the interface for Filter types
 *
 *
 *
 * COPYRIGHT:
 * 
 * 
 * Copyright (c) 1999/2000 JJ2000 Partners.
 * */
package jj2000.j2k.wavelet;

/**
 * This interface defines the identifiers for the different types of filters that are supported.
 *
 * <p>The identifier values are the same as those used in the codestream syntax, for the filters that are defined in the standard.</p>
 *
 */
public interface FilterTypes
{
  /**
   * W7x9 filter: 0x00
   */
  public final static int W9X7 = 0;
  /**
   * W5x3 filter: 0x01
   */
  public final static int W5X3 = 1;
  /**
   * User-defined filter: -1
   */
  public final static int CUSTOM = -1;
}
