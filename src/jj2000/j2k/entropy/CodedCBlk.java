/*
 * CVS identifier:
 *
 * $Id: CodedCBlk.java,v 1.9 2001/08/17 09:42:13 grosbois Exp $
 *
 * Class:                   CodedCBlk
 *
 * Description:             The generic coded (compressed) code-block
 *
 *
 *
 * COPYRIGHT:
 * 
 * 
 * Copyright (c) 1999/2000 JJ2000 Partners.
 * */
package jj2000.j2k.entropy;

/**
 * This is the generic class to store coded (compressed) code-block. It stores the compressed data as well as the necessary side-information.
 *
 * <p>This class is normally not used. Instead the EncRDCBlk, EncLyrdCBlk and the DecLyrdCBlk subclasses are used.</p>
 *
 * @see jj2000.j2k.entropy.encoder.CBlkRateDistStats
 * @see jj2000.j2k.entropy.decoder.DecLyrdCBlk
 *
 */
public class CodedCBlk
{
  /**
   * The horizontal index of the code-block, within the subband.
   */
  public int n;
  /**
   * The vertical index of the code-block, within the subband.
   */
  public int m;
  /**
   * The number of skipped most significant bit-planes.
   */
  public int skipMSBP;
  /**
   * The compressed data
   */
  public byte data[];

  /**
   * Creates a new CodedCBlk object wit the default values and without allocating any space for its members.
     *
   */
  public CodedCBlk()
  {
  }

  /**
   * Creates a new CodedCBlk object with the specified values.
   *
   * @param m The horizontal index of the code-block, within the subband.
   *
   * @param n The vertical index of the code-block, within the subband.
   *
   * @param skipMSBP The number of skipped most significant bit-planes for this code-block.
   *
   * @param data The compressed data. This array is referenced by this object so it should not be modified after.
     *
   */
  public CodedCBlk(int m, int n, int skipMSBP, byte data[])
  {
    this.m = m;
    this.n = n;
    this.skipMSBP = skipMSBP;
    this.data = data;
  }

  /**
   * Returns the contents of the object in a string. The string contains the following data: 'm', 'n', 'skipMSBP' and 'data.length. This is used for debugging.
   *
   * @return A string with the contents of the object
     *
   */
  public String toString()
  {
    return "m=" + m + ", n=" + n + ", skipMSBP=" + skipMSBP
      + ", data.length=" + ((data != null) ? "" + data.length : "(null)");
  }
}
