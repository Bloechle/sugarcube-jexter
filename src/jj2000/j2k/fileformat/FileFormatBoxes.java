/*
 * cvs identifier:
 *
 * $Id: FileFormatBoxes.java,v 1.10 2001/02/14 12:22:20 qtxjoas Exp $
 *
 * Class:                   FileFormatMarkers
 *
 * Description:             Contains definitions of boxes used in jp2 files
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
package jj2000.j2k.fileformat;

/**
 * This class contains all the markers used in the JPEG 2000 Part I file format
 *
 * @see jj2000.j2k.fileformat.writer.FileFormatWriter
 *
 * @see jj2000.j2k.fileformat.reader.FileFormatReader
 *
 */
public interface FileFormatBoxes
{
  /**
   * ** Main boxes ***
   */
  public static final int JP2_SIGNATURE_BOX = 0x6a502020;
  public static final int FILE_TYPE_BOX = 0x66747970;
  public static final int JP2_HEADER_BOX = 0x6a703268;
  public static final int CONTIGUOUS_CODESTREAM_BOX = 0x6a703263;
  public static final int INTELLECTUAL_PROPERTY_BOX = 0x64703269;
  public static final int XML_BOX = 0x786d6c20;
  public static final int UUID_BOX = 0x75756964;
  public static final int UUID_INFO_BOX = 0x75696e66;
  /**
   * JP2 Header boxes
   */
  public static final int IMAGE_HEADER_BOX = 0x69686472;
  public static final int BITS_PER_COMPONENT_BOX = 0x62706363;
  public static final int COLOUR_SPECIFICATION_BOX = 0x636f6c72;
  public static final int PALETTE_BOX = 0x70636c72;
  public static final int COMPONENT_MAPPING_BOX = 0x636d6170;
  public static final int CHANNEL_DEFINITION_BOX = 0x63646566;
  public static final int RESOLUTION_BOX = 0x72657320;
  public static final int CAPTURE_RESOLUTION_BOX = 0x72657363;
  public static final int DEFAULT_DISPLAY_RESOLUTION_BOX = 0x72657364;
  public static final int READER_REQUIREMENTS_BOX = 0x72726571;//added by zoubi
  /**
   * End of JP2 Header boxes
   */
  /**
   * UUID Info Boxes
   */
  public static final int UUID_LIST_BOX = 0x75637374;
  public static final int URL_BOX = 0x75726c20;
  /**
   * end of UUID Info boxes
   */
  /**
   * Image Header Box Fields
   */
  public static final int IMB_VERS = 0x0100;
  public static final int IMB_C = 7;
  public static final int IMB_UnkC = 1;
  public static final int IMB_IPR = 0;
  /**
   * end of Image Header Box Fields
   */
  /**
   * Colour Specification Box Fields
   */
  public static final int CSB_METH = 1;
  public static final int CSB_PREC = 0;
  public static final int CSB_APPROX = 0;
  public static final int CSB_ENUM_SRGB = 16;
  public static final int CSB_ENUM_GREY = 17;
  /**
   * en of Colour Specification Box Fields
   */
  /**
   * File Type Fields
   */
  public static final int FT_BR = 0x6a703220;
}
