/*
 * CVS identifier:
 *
 * $Id : $
 *
 * Class:                   Progression
 *
 * Description:             Holds the type(s) of progression
 *
 *
 * Modified by:
 *
 * COPYRIGHT:
 * 
 * Copyright (c) 1999/2000 JJ2000 Partners.
 * 
 * 
 * 
 */
package jj2000.j2k.entropy;

import jj2000.j2k.codestream.ProgressionType;

/**
 * This class holds one of the different progression orders defined in the bit stream. The type(s) of progression order are defined in the ProgressionType
 * interface. A Progression object is totally defined by its component start and end, resolution level start and end and layer start and end indexes. If no
 * progression order change is defined, there is only Progression instance.
 *
 * @see ProgressionType
 *
 */
public class Progression implements ProgressionType
{
  /**
   * Progression type as defined in ProgressionType interface
   */
  public int type;
  /**
   * Component index for the start of a progression
   */
  public int cs;
  /**
   * Component index for the end of a progression.
   */
  public int ce;
  /**
   * Resolution index for the start of a progression
   */
  public int rs;
  /**
   * Resolution index for the end of a progression.
   */
  public int re;
  /**
   * The index of the last layer.
   */
  public int lye;

  /**
   * Constructor.
   *
   * Builds a new Progression object with specified type and bounds of progression.
   *
   * @param type The progression type
   *
   * @param cs The component index start
   *
   * @param ce The component index end
   *
   * @param rs The resolution level index start
   *
   * @param re The resolution level index end
   *
   * @param lye The layer index end
   *
   */
  public Progression(int type, int cs, int ce, int rs, int re, int lye)
  {
    this.type = type;
    this.cs = cs;
    this.ce = ce;
    this.rs = rs;
    this.re = re;
    this.lye = lye;
  }

  public String toString()
  {
    String str = "type= ";
    switch (type)
    {
      case LY_RES_COMP_POS_PROG:
        str += "layer, ";
        break;
      case RES_LY_COMP_POS_PROG:
        str += "res, ";
        break;
      case RES_POS_COMP_LY_PROG:
        str += "res-pos, ";
        break;
      case POS_COMP_RES_LY_PROG:
        str += "pos-comp, ";
        break;
      case COMP_POS_RES_LY_PROG:
        str += "pos-comp, ";
        break;
      default:
        throw new Error("Unknown progression type");
    }
    str += "comp.: " + cs + "-" + ce + ", ";
    str += "res.: " + rs + "-" + re + ", ";
    str += "layer: up to " + lye;
    return str;
  }
}
