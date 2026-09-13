/*
 * CVS Identifier:
 *
 * $Id: BinaryDataInput.java,v 1.12 2001/07/23 09:27:26 grosbois Exp $
 *
 * Interface:           BinaryDataInput
 *
 * Description:         Stream like interface for binary
 *                      input from a stream or file.
 *
 *
 *
 * COPYRIGHT:
 * 
 * 
 * Copyright (c) 1999/2000 JJ2000 Partners.
 * */
package jj2000.j2k.io;

import java.io.EOFException;
import java.io.IOException;

/**
 * This interface defines the input of binary data from streams and/or files.
 *
 * <p>Byte level input (i.e., for byte, int, long, float, etc.) should always be byte aligned. For example, a request to read an <tt>int</tt> should always
 * realign the input at the byte level.</p>
 *
 * <p>The implementation of this interface should clearly define if multi-byte input data is read in little- or big-endian byte ordering (least significant byte
 * first or most significant byte first, respectively).</p>
 *
 * @see EndianType
 *
 */
public interface BinaryDataInput
{
  /**
   * Should read a signed byte (i.e., 8 bit) from the input. reading, the input should be realigned at the byte level.
   *
   * @return The next byte-aligned signed byte (8 bit) from the input.
   *
   * @exception EOFException If the end-of file was reached before getting all the necessary data.
   *
   * @exception IOException If an I/O error ocurred.
     *
   */
  public byte readByte() throws EOFException, IOException;

  /**
   * Should read an unsigned byte (i.e., 8 bit) from the input. It is returned as an <tt>int</tt> since Java does not have an unsigned byte type. Prior to
   * reading, the input should be realigned at the byte level.
   *
   * @return The next byte-aligned unsigned byte (8 bit) from the input, as an <tt>int</tt>.
   *
   * @exception EOFException If the end-of file was reached before getting all the necessary data.
   *
   * @exception IOException If an I/O error ocurred.
     *
   */
  public int readUnsignedByte() throws EOFException, IOException;

  /**
   * Should read a signed short (i.e., 16 bit) from the input. Prior to reading, the input should be realigned at the byte level.
   *
   * @return The next byte-aligned signed short (16 bit) from the input.
   *
   * @exception EOFException If the end-of file was reached before getting all the necessary data.
   *
   * @exception IOException If an I/O error ocurred.
     *
   */
  public short readShort() throws EOFException, IOException;

  /**
   * Should read an unsigned short (i.e., 16 bit) from the input. It is returned as an <tt>int</tt> since Java does not have an unsigned short type. Prior to
   * reading, the input should be realigned at the byte level.
   *
   * @return The next byte-aligned unsigned short (16 bit) from the input, as an <tt>int</tt>.
   *
   * @exception EOFException If the end-of file was reached before getting all the necessary data.
   *
   * @exception IOException If an I/O error ocurred.
     *
   */
  public int readUnsignedShort() throws EOFException, IOException;

  /**
   * Should read a signed int (i.e., 32 bit) from the input. Prior to reading, the input should be realigned at the byte level.
   *
   * @return The next byte-aligned signed int (32 bit) from the input.
   *
   * @exception EOFException If the end-of file was reached before getting all the necessary data.
   *
   * @exception IOException If an I/O error ocurred.
     *
   */
  public int readInt() throws EOFException, IOException;

  /**
   * Should read an unsigned int (i.e., 32 bit) from the input. It is returned as a <tt>long</tt> since Java does not have an unsigned short type. Prior to
   * reading, the input should be realigned at the byte level.
   *
   * @return The next byte-aligned unsigned int (32 bit) from the input, as a <tt>long</tt>.
   *
   * @exception EOFException If the end-of file was reached before getting all the necessary data.
   *
   * @exception IOException If an I/O error ocurred.
     *
   */
  public long readUnsignedInt() throws EOFException, IOException;

  /**
   * Should read a signed long (i.e., 64 bit) from the input. Prior to reading, the input should be realigned at the byte level.
   *
   * @return The next byte-aligned signed long (64 bit) from the input.
   *
   * @exception EOFException If the end-of file was reached before getting all the necessary data.
   *
   * @exception IOException If an I/O error ocurred.
     *
   */
  public long readLong() throws EOFException, IOException;

  /**
   * Should read an IEEE single precision (i.e., 32 bit) floating-point number from the input. Prior to reading, the input should be realigned at the byte
   * level.
   *
   * @return The next byte-aligned IEEE float (32 bit) from the input.
   *
   * @exception EOFException If the end-of file was reached before getting all the necessary data.
   *
   * @exception IOException If an I/O error ocurred.
     *
   */
  public float readFloat() throws EOFException, IOException;

  /**
   * Should read an IEEE double precision (i.e., 64 bit) floating-point number from the input. Prior to reading, the input should be realigned at the byte
   * level.
   *
   * @return The next byte-aligned IEEE double (64 bit) from the input.
   *
   * @exception EOFException If the end-of file was reached before getting all the necessary data.
   *
   * @exception IOException If an I/O error ocurred.
     *
   */
  public double readDouble() throws EOFException, IOException;

  /**
   * Returns the endianess (i.e., byte ordering) of the implementing class. Note that an implementing class may implement only one type of endianness or both,
   * which would be decided at creatiuon time.
   *
   * @return Either <tt>EndianType.BIG_ENDIAN</tt> or <tt>EndianType.LITTLE_ENDIAN</tt>
   *
   * @see EndianType
     *
   */
  public int getByteOrdering();

  /**
   * Skips <tt>n</tt> bytes from the input. Prior to skipping, the input should be realigned at the byte level.
   *
   * @param n The number of bytes to skip
   *
   * @exception EOFException If the end-of file was reached before all the bytes could be skipped.
   *
   * @exception IOException If an I/O error ocurred.
     *
   */
  public int skipBytes(int n) throws EOFException, IOException;
}
