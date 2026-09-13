/* 
 * CVS identifier:
 * 
 * $Id: NativeServices.java,v 1.7 2000/09/05 09:25:26 grosbois Exp $
 * 
 * Class:                   NativeServices
 * 
 * Description:             Static methods allowing to access to some
 *                          native services. It uses native methods.
 * 
 * 
 * 
 * COPYRIGHT:
 * 
 * Copyright (c) 1999/2000 JJ2000 Partners.
 * 
 * 
 * 
 */
package jj2000.j2k.util;

/**
 * This class presents a collection of static methods that allow access to some native methods. It makes use of native methods to access those thread
 * properties.
 *
 * <P>Since the methods in this class require the presence of a shared library with the name defined in SHLIB_NAME it is necessary to load it prior to making
 * use of any such methods. All methods that require the shared library will automatically load the library if that has not been already done. The library might
 * also be manually loaded with the 'loadLibrary()' method of this class.
 *
 * <P>This class provides only static methods. It should not be instantiated.
 *
 * <P>Currently the only native services available is settings relative to POSIX threads, which are not accessible from the Java API.
 *
 * <P>Currently the methods in this class make sense with POSIX threads only, since they access POSIX threads settings. POSIX threads are most used under UNIX
 * and UNIX-like operating systems and are mostly referred to as "native" threads in Java Virtual Machine (JVM) implementations.
 *
 * <P>The shared library SHLIB_NAME uses functions of the POSIX thread library (i.e. 'pthread'). Calling the methods that use the 'pthread' library will most
 * prbably cause the Java Virtual Machine (JVM) to crash if it is not using the POSIX threads, due to unsatisfied references. For instance, JVMs that use
 * "green" threads will most certainly crash. POSIX threads are referred to as "native" threads in JVMs under UNIX operating systems.
 *
 * <P>On Operating Systems where POSIX threads are not available (typically Windows 95/98/NT/2000, MacIntosh, OS/2) there is no problem since the SHLIB_NAME, if
 * available, will not make use of POSIX threads library functions, thus no problem should occur.
 *
 */
@SuppressWarnings({ "rawtypes", "unchecked", "cast", "this-escape", "serial", "static",
        "removal", "overrides", "lossy-conversions", "fallthrough", "deprecation" })   // vendored JJ2000: upstream code, kept as it is
public final class NativeServices
{
  /**
   * The name of the shared library containing the implementation of the native methods: 'jj2000'. The actual file name of the library is system dependent.
   * Under UNIX it will be 'libjj2000.so', while under Windows it will be 'jj2000.dll'.
     *
   */
  public static final String SHLIB_NAME = "jj2000";
  /**
   * The state of the library loading
   */
  private static int libState;
  /**
   * Library load state ID indicating that no attept to load the library has been done yet.
   */
  private final static int LIB_STATE_NOT_LOADED = 0;
  /**
   * Library load state ID indicating that the library was successfully loaded.
   */
  private final static int LIB_STATE_LOADED = 1;
  /**
   * Library load state ID indicating that an attempt to load the library was done and that it could not be found.
   */
  private final static int LIB_STATE_NOT_FOUND = 2;

  /**
   * Private and only constructor, so that no class instance might be created. Since all methods are static creating a class instance is useless. If called it
   * throws an 'IllegalArgumentException'.
     *
   */
  private NativeServices()
  {
    throw new IllegalArgumentException("Class can not be instantiated");
  }

  /**
   * Sets the concurrency level of the threading system of the Java Virtual Machine (JVM) to the specified level. The concurrency level specifies how many
   * threads can run in parallel on different CPUs at any given time for JVM implementations that use POSIX threads with PTHREAD_SCOPE_PROCESS scheduling scope.
   * A concurrency level of 0 means that the operating system will automatically adjust the concurrency level depending on the number of threads blocking on
   * system calls, but this will probably not exploit more than one CPU in multiporocessor machines. If the concurrency level if set to more than the number of
   * available processors in the machine the performance might degrade.
   *
   * <P>For JVM implementations that use POSIX threads with PTHREAD_SCOPE_SYSTEM scheduling scope or JVM implementations that use Windows(R) threads and maybe
   * others, setting the concurrency level has no effect. In this cases the number of CPUs that can be exploited by the JVM is not limited in principle, all
   * CPUs are available to the JVM.
   *
   * <P>For JVM implementations that use "green" threads setting the concurrency level, and thus calling this method, makes no sense, since "green" threads are
   * all contained in one user process and can not use multiple CPUs. In fact calling this method can result in a JVM crash is the shared library SHLIB_NAME has
   * been compiled to use POSIX threads.
   *
   * @param n The new concurrency level to set.
   *
   * @exception IllegalArgumentException Concurrency level is negative
   *
   * @exception UnsatisfiedLinkError If the shared native library implementing the functionality could not be loaded.
     *
   */
  public static void setThreadConcurrency(int n)
  {
    // Check that the library is loaded
    checkLibrary();
    // Check argument
    if (n < 0)
      throw new IllegalArgumentException();
    // Set concurrency with native method
    setThreadConcurrencyN(n);
  }

  /**
   * Calls the POSIX threads 'pthread_setconcurrency', or equivalent, function with 'level' as the argument.
     *
   */
  private static native void setThreadConcurrencyN(int level);

  /**
   * Returns the current concurrency level. See 'setThreadConcurrency' for details on the meaning of concurrency
   *
   * @return The current concurrency level
   *
   * @see #setThreadConcurrency
     *
   */
  public static int getThreadConcurrency()
  {
    // Check that the library is loaded
    checkLibrary();
    // Return concurrency from native method
    return getThreadConcurrencyN();
  }

  /**
   * Calls the POSIX threads 'pthread_getconcurrency', or equivalent, function and return the result.
   *
   * @return The current concurrency level.
     *
   */
  private static native int getThreadConcurrencyN();

  /**
   * Loads the shared library implementing the native methods of this class and returns true on success. Multiple calls to this method after a successful call
   * have no effect and return true. Multiple calls to this method after unsuccesful calls will make new attempts to load the library.
   *
   * @return True if the libary could be loaded or is already loaded. False if the library can not be found and loaded.
     *
   */
  public static boolean loadLibrary()
  {
    // If already loaded return true without doing anything
    if (libState == LIB_STATE_LOADED)
      return true;
    // Try to load the library
    try
    {
      System.loadLibrary(SHLIB_NAME);
    }
    catch (UnsatisfiedLinkError e)
    {
      // Library was not found
      libState = LIB_STATE_NOT_FOUND;
      return false;
    }
    // Library was found
    libState = LIB_STATE_LOADED;
    return true;
  }

  /**
   * Checks if the library SHLIB_NAME is already loaded and attempts to load if not yet loaded. If the library can not be found (either in a previous attempt to
   * load it or in an attempt in this method) an 'UnsatisfiedLinkError' exception is thrown.
   *
   * @exception UnsatisfiedLinkError If the library SHLIB_NAME can not be found.
     *
   */
  private static void checkLibrary()
  {
    switch (libState)
    {
      case LIB_STATE_LOADED: // Already loaded, nothing to do
        return;
      case LIB_STATE_NOT_LOADED: // Not yet loaded => load now
        // If load successful break, otherwise continue to the
        // LIB_STATE_NOT_LOADED state
        if (loadLibrary())
          break;
      case LIB_STATE_NOT_FOUND: // Could not be found, throw exception
        throw new UnsatisfiedLinkError("NativeServices: native shared "
          + "library could not be loaded");
    }
  }
}
