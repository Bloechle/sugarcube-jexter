/*
 * CVS identifier:
 *
 * $Id: DecLyrdCBlk.java,v 1.9 2001/09/14 09:25:01 grosbois Exp $
 *
 * Class:                   DecLyrdCBlk
 *
 * Description:             The coded (compressed) code-block
 *                          with layered organization for the decoder.
 *
 *
 *
 * COPYRIGHT:
 * 
 * 
 * Copyright (c) 1999/2000 JJ2000 Partners.
 * */
package jj2000.j2k.entropy.decoder;

import jj2000.j2k.entropy.CodedCBlk;

/**
 * This class stores coded (compressed) code-blocks that are organized in layers. This object can contain either all code-block data (i.e. all layers), or a
 * subset of all the layers that make up the whole compressed code-block. It is applicable to the decoder engine only. Some data of the coded-block is stored in
 * the super class, see CodedCBlk.
 *
 * <p>A code-block may have its progressive attribute set (i.e. the 'prog' flag is true). If a code-block is progressive then it means that more data for it may
 * be obtained for an improved quality. If the progressive flag is false then no more data is available from the source for this code-block.</p>
 *
 * @see CodedCBlk
 *
 */
public class DecLyrdCBlk extends CodedCBlk
{
  /**
   * The horizontal coordinate of the upper-left corner of the code-block
   */
  public int ulx;
  /**
   * The vertical coordinate of the upper left corner of the code-block
   */
  public int uly;
  /**
   * The width of the code-block
   */
  public int w;
  /**
   * The height of the code-block
   */
  public int h;
  /**
   * The coded (compressed) data length. The data is stored in the 'data' array (see super class).
   */
  public int dl;
  /**
   * The progressive flag, false by default (see above).
   */
  public boolean prog;
  /**
   * The number of layers in the coded data.
   */
  public int nl;
  /**
   * The index of the first truncation point returned
   */
  public int ftpIdx;
  /**
   * The total number of truncation points from layer 1 to the last one in this object. The number of truncation points in 'data' is 'nTrunc-ftpIdx'.
   */
  public int nTrunc;
  /**
   * The length of each terminated segment. If null then there is only one terminated segment, and its length is 'dl'. The number of terminated segments is to
   * be deduced from 'ftpIdx', 'nTrunc' and the coding options. This array contains all terminated segments from the 'ftpIdx' truncation point, upto, and
   * including, the 'nTrunc-1' truncation point. Any data after 'nTrunc-1' is not included in any length.
   */
  public int tsLengths[];

  /**
   * Object information in a string
   *
   * @return Information in a string
     *
   */
  public String toString()
  {
    String str =
      "Coded code-block (" + m + "," + n + "): " + skipMSBP + " MSB skipped, "
      + dl + " bytes, " + nTrunc + " truncation points, " + nl + " layers, "
      + "progressive=" + prog + ", ulx=" + ulx + ", uly=" + uly
      + ", w=" + w + ", h=" + h + ", ftpIdx=" + ftpIdx;
    if (tsLengths != null)
    {
      str += " {";
      for (int i = 0; i < tsLengths.length; i++)
        str += " " + tsLengths[i];
      str += " }";
    }
    return str;
  }
}
