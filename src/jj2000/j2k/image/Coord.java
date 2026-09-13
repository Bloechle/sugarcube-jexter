/*
 * CVS identifier:
 *
 * $Id: Coord.java,v 1.14 2002/04/30 13:18:24 grosbois Exp $
 *
 * Class:                   Coord
 *
 * Description:             Class for storage of 2-D coordinates
 *
 *
 *
 * COPYRIGHT:
 * 
 * 
 * Copyright (c) 1999/2000 JJ2000 Partners.
 * */
package jj2000.j2k.image;

/**
 * This class represents 2-D coordinates.
 *
 */
public class Coord
{
  /**
   * The horizontal coordinate
   */
  public int x;
  /**
   * The vertical coordinate
   */
  public int y;

  /**
   * Creates a new coordinate object given with the (0,0) coordinates
     *
   */
  public Coord()
  {
  }

  /**
   * Creates a new coordinate object given the two coordinates.
   *
   * @param x The horizontal coordinate.
   *
   * @param y The vertical coordinate.
     *
   */
  public Coord(int x, int y)
  {
    this.x = x;
    this.y = y;
  }

  /**
   * Creates a new coordinate object given another Coord object i.e. copy constructor
   *
   * @param c The Coord object to be copied.
     *
   */
  public Coord(Coord c)
  {
    this.x = c.x;
    this.y = c.y;
  }

  /**
   * Returns a string representation of the object coordinates
   *
   * @return The vertical and the horizontal coordinates
     *
   */
  public String toString()
  {
    return "(" + x + "," + y + ")";
  }
}
