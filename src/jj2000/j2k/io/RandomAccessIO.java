/*
 * CVS Identifier:
 *
 * $Id: RandomAccessIO.java,v 1.15 2001/10/24 12:07:02 grosbois Exp $
 *
 * Interface:           RandomAccessIO.java
 *
 * Description:         Interface definition for random access I/O.
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
 * This abstract class defines the interface to perform random access I/O. It implements the <tt>BinaryDataInput</tt> and <tt>BinaryDataOutput</tt> interfaces
 * so that binary data input/output can be performed.
 *
 * <p>This interface supports streams of up to 2 GB in length.</p>
 *
 * @see BinaryDataInput
 * @see BinaryDataOutput
 *
 */
public interface RandomAccessIO
  extends BinaryDataInput, BinaryDataOutput
{
  /**
   * Closes the I/O stream. Prior to closing the stream, any buffered data (at the bit and byte level) should be written.
   *
   * @exception IOException If an I/O error ocurred. 
     *
   */
  public void close() throws IOException;

  /**
   * Returns the current position in the stream, which is the position from where the next byte of data would be read. The first byte in the stream is in
   * position <tt>0</tt>.
   *
   * @return The offset of the current position, in bytes.
   *
   * @exception IOException If an I/O error ocurred.
     *
   */
  public int getPos() throws IOException;

  /**
   * Returns the current length of the stream, in bytes, taking into account any buffering.
   *
   * @return The length of the stream, in bytes.
   *
   * @exception IOException If an I/O error ocurred. 
     *
   */
  public int length() throws IOException;

  /**
   * Moves the current position for the next read or write operation to offset. The offset is measured from the beginning of the stream. The offset may be set
   * beyond the end of the file, if in write mode. Setting the offset beyond the end of the file does not change the file length. The file length will change
   * only by writing after the offset has been set beyond the end of the file.
   *
   * @param off The offset where to move to.
   *
   * @exception EOFException If in read-only and seeking beyond EOF.
   *
   * @exception IOException If an I/O error ocurred.
     *
   */
  public void seek(int off) throws IOException;

  /**
   * Reads a byte of data from the stream. Prior to reading, the stream is realigned at the byte level.
   *
   * @return The byte read, as an int.
   *
   * @exception EOFException If the end-of file was reached.
   *
   * @exception IOException If an I/O error ocurred.
     *
   */
  public int read() throws EOFException, IOException;

  /**
   * Reads up to len bytes of data from this file into an array of bytes. This method reads repeatedly from the stream until all the bytes are read. This method
   * blocks until all the bytes are read, the end of the stream is detected, or an exception is thrown.
   *
   * @param b The buffer into which the data is to be read. It must be long enough.
   *
   * @param off The index in 'b' where to place the first byte read.
   *
   * @param len The number of bytes to read.
   *
   * @exception EOFException If the end-of file was reached before getting all the necessary data.
   *
   * @exception IOException If an I/O error ocurred.
     *
   */
  public void readFully(byte b[], int off, int len) throws IOException;

  /**
   * Writes a byte to the stream. Prior to writing, the stream is realigned at the byte level.
   *
   * @param b The byte to write. The lower 8 bits of <tt>b</tt> are written.
   *
   * @exception IOException If an I/O error ocurred. 
     *
   */
  public void write(int b) throws IOException;
}
