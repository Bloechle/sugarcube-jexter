/*
 * CVS identifier:
 *
 * $Id: ArrayUtil.java,v 1.10 2000/09/05 09:25:15 grosbois Exp $
 *
 * Class:                   ArrayUtil
 *
 * Description:             Utillities for arrays.
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
package jj2000.j2k.util;

/**
 * This class contains a colleaction of utility static methods for arrays.
 *
 */
public class ArrayUtil
{
  /**
   * The maximum array size to do element by element copying, larger arrays are copyied in a n optimized way.
   */
  public static final int MAX_EL_COPYING = 8;
  /**
   * The number of elements to copy initially in an optimized array copy
   */
  public static final int INIT_EL_COPYING = 4;

  /**
   * Reinitializes an int array to the given value in an optimized way. If the length of the array is less than MAX_EL_COPYING, then the array is set element by
   * element in the normal way, otherwise the first INIT_EL_COPYING elements are set element by element and then System.arraycopy is used to set the other parts
   * of the array.
   *
   * @param arr The array to set.
   *
   * @param val The value to set the array to.
   *
   *
   *
   */
  public static void intArraySet(int arr[], int val)
  {
    int i, len, len2;

    len = arr.length;
    // Set array to 'val' in an optimized way
    if (len < MAX_EL_COPYING)
      // Not worth doing optimized way
      for (i = len - 1; i >= 0; i--) // Set elements
        arr[i] = val;
    else
    { // Do in optimized way
      len2 = len >> 1;
      for (i = 0; i < INIT_EL_COPYING; i++) // Set first elements
        arr[i] = val;
      for (; i <= len2; i <<= 1)
        // Copy values doubling size each time
        System.arraycopy(arr, 0, arr, i, i);
      if (i < len) // Copy values to end
        System.arraycopy(arr, 0, arr, i, len - i);
    }
  }

  /**
   * Reinitializes a byte array to the given value in an optimized way. If the length of the array is less than MAX_EL_COPYING, then the array is set element by
   * element in the normal way, otherwise the first INIT_EL_COPYING elements are set element by element and then System.arraycopy is used to set the other parts
   * of the array.
   *
   * @param arr The array to set.
   *
   * @param val The value to set the array to.
   *
   *
   *
   */
  public static void byteArraySet(byte arr[], byte val)
  {
    int i, len, len2;

    len = arr.length;
    // Set array to 'val' in an optimized way
    if (len < MAX_EL_COPYING)
      // Not worth doing optimized way
      for (i = len - 1; i >= 0; i--) // Set elements
        arr[i] = val;
    else
    { // Do in optimized way
      len2 = len >> 1;
      for (i = 0; i < INIT_EL_COPYING; i++) // Set first elements
        arr[i] = val;
      for (; i <= len2; i <<= 1)
        // Copy values doubling size each time
        System.arraycopy(arr, 0, arr, i, i);
      if (i < len) // Copy values to end
        System.arraycopy(arr, 0, arr, i, len - i);
    }
  }
}
