/*
 * CVS Identifier:
 *
 * $Id: EndianType.java,v 1.10 2000/09/05 09:24:36 grosbois Exp $
 *
 * Interface:           EndianType
 *
 * Description:         Defines the two types of endianess (i.e. byte
 *                      ordering).
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
package jj2000.j2k.io;

/**
 * This interface defines constants for the two types of byte ordering: little- and big-endian.
 *
 * <P>Little-endian is least significant byte first.
 *
 * <P>Big-endian is most significant byte first.
 *
 * <P>This interface defines the constants only. In order to use the constants in any other class you can either use the fully qualified name (e.g.,
 * <tt>EndianType.LITTLE_ENDIAN</tt>) or declare this interface in the implements clause of the class and then access the identifier directly.
 *
 */
public interface EndianType
{
  /**
   * Identifier for big-endian byte ordering (i.e. most significant byte first)
   */
  public static final int BIG_ENDIAN = 0;
  /**
   * Identifier for little-endian byte ordering (i.e. least significant byte first)
   */
  public static final int LITTLE_ENDIAN = 1;
}
