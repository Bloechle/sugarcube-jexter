/* 
 * CVS identifier:
 * 
 * $Id: QuantTypeSpec.java,v 1.18 2001/10/24 12:05:18 grosbois Exp $
 * 
 * Class:                   QuantTypeSpec
 * 
 * Description:             Quantization type specifications
 * 
 * 
 * 
 * COPYRIGHT:
 * 
 * 
 * Copyright (c) 1999/2000 JJ2000 Partners.
 * */
package jj2000.j2k.quantization;

import jj2000.j2k.ModuleSpec;
import jj2000.j2k.util.ParameterList;

import java.util.StringTokenizer;

/**
 * This class extends ModuleSpec class in order to hold specifications about the quantization type to use in each tile-component. Supported quantization type
 * are:<br>
 *
 * <ul> <li> Reversible (no quantization)</li> <li> Derived (the quantization step size is derived from the one of the LL-subband)</li> <li> Expounded (the
 * quantization step size of each subband is signalled in the codestream headers) </li> </ul>
 *
 * @see ModuleSpec
 *
 */
@SuppressWarnings({ "rawtypes", "unchecked", "cast", "this-escape", "serial", "static",
        "removal", "overrides", "lossy-conversions", "fallthrough", "deprecation" })   // vendored JJ2000: upstream code, kept as it is
public class QuantTypeSpec extends ModuleSpec
{
  /**
   * Constructs an empty 'QuantTypeSpec' with the specified number of tiles and components. This constructor is called by the decoder.
   *
   * @param nt Number of tiles
   *
   * @param nc Number of components
   *
   * @param type the type of the allowed specifications for this module i.e. tile specific, component specific or both.
     *
   */
  public QuantTypeSpec(int nt, int nc, byte type)
  {
    super(nt, nc, type);
  }

  /**
   * Constructs a new 'QuantTypeSpec' for the specified number of components and tiles and the arguments of "-Qtype" option. This constructor is called by the
   * encoder.
   *
   * @param nt The number of tiles
   *
   * @param nc The number of components
   *
   * @param type the type of the specification module i.e. tile specific, component specific or both.
   *
   * @param pl The ParameterList
     *
   */
  public QuantTypeSpec(int nt, int nc, byte type, ParameterList pl)
  {
    super(nt, nc, type);

    String param = pl.value("Qtype");
    if (param == null)
    {
      if (pl.bool("lossless"))
        setDefault("reversible");
      else
        setDefault("expounded");
      return;
    }

    // Parse argument
    StringTokenizer stk = new StringTokenizer(param);
    String word; // current word
    byte curSpecValType = SPEC_DEF; // Specification type of the
    // current parameter
    boolean[] tileSpec = null; // Tiles concerned by the specification
    boolean[] compSpec = null; // Components concerned by the specification

    while (stk.hasMoreTokens())
    {
      word = stk.nextToken().toLowerCase();

      switch (word.charAt(0))
      {
        case 't': // Tiles specification
          tileSpec = parseIdx(word, nTiles);

          if (curSpecValType == SPEC_COMP_DEF)
            curSpecValType = SPEC_TILE_COMP;
          else
            curSpecValType = SPEC_TILE_DEF;
          break;
        case 'c': // Components specification
          compSpec = parseIdx(word, nComp);

          if (curSpecValType == SPEC_TILE_DEF)
            curSpecValType = SPEC_TILE_COMP;
          else
            curSpecValType = SPEC_COMP_DEF;
          break;
        case 'r': // reversible specification
        case 'd': // derived quantization step size specification
        case 'e': // expounded quantization step size specification
          if (!word.equalsIgnoreCase("reversible")
            && !word.equalsIgnoreCase("derived")
            && !word.equalsIgnoreCase("expounded"))
            throw new IllegalArgumentException("Unknown parameter "
              + "for "
              + "'-Qtype' option: "
              + word);

          if (pl.bool("lossless")
            && (word.equalsIgnoreCase("derived")
            || word.equalsIgnoreCase("expounded")))
            throw new IllegalArgumentException("Cannot use non "
              + "reversible "
              + "quantization with "
              + "'-lossless' option");

          if (curSpecValType == SPEC_DEF) // Default specification
            setDefault(word);
          else if (curSpecValType == SPEC_TILE_DEF)
          {
            // Tile default specification
            for (int i = tileSpec.length - 1; i >= 0; i--)
              if (tileSpec[i])
                setTileDef(i, word);
          }
          else if (curSpecValType == SPEC_COMP_DEF)
          {
            // Component default specification 
            for (int i = compSpec.length - 1; i >= 0; i--)
              if (compSpec[i])
                setCompDef(i, word);
          }
          else // Tile-component specification
            for (int i = tileSpec.length - 1; i >= 0; i--)
              for (int j = compSpec.length - 1; j >= 0; j--)
                if (tileSpec[i] && compSpec[j])
                  setTileCompVal(i, j, word);

          // Re-initialize
          curSpecValType = SPEC_DEF;
          tileSpec = null;
          compSpec = null;
          break;

        default:
          throw new IllegalArgumentException("Unknown parameter for "
            + "'-Qtype' option: " + word);
      }
    }

    // Check that default value has been specified
    if (getDefault() == null)
    {
      int ndefspec = 0;
      for (int t = nt - 1; t >= 0; t--)
        for (int c = nc - 1; c >= 0; c--)
          if (specValType[t][c] == SPEC_DEF)
            ndefspec++;

      // If some tile-component have received no specification, the
      // quantization type is 'reversible' (if '-lossless' is specified)
      // or 'expounded' (if not). 
      if (ndefspec != 0)
        if (pl.bool("lossless"))
          setDefault("reversible");
        else
          setDefault("expounded");
      else
      {
        // All tile-component have been specified, takes arbitrarily
        // the first tile-component value as default and modifies the
        // specification type of all tile-component sharing this
        // value.
        setDefault(getTileCompVal(0, 0));

        switch (specValType[0][0])
        {
          case SPEC_TILE_DEF:
            for (int c = nc - 1; c >= 0; c--)
              if (specValType[0][c] == SPEC_TILE_DEF)
                specValType[0][c] = SPEC_DEF;
            tileDef[0] = null;
            break;
          case SPEC_COMP_DEF:
            for (int t = nt - 1; t >= 0; t--)
              if (specValType[t][0] == SPEC_COMP_DEF)
                specValType[t][0] = SPEC_DEF;
            compDef[0] = null;
            break;
          case SPEC_TILE_COMP:
            specValType[0][0] = SPEC_DEF;
            tileCompVal.put("t0c0", null);
            break;
        }
      }
    }
  }

  /**
   * Returns true if given tile-component uses derived quantization step size.
   *
   * @param t Tile index
   *
   * @param c Component index
   *
   * @return True if derived quantization step size
     *
   */
  public boolean isDerived(int t, int c)
  {
    if (((String) getTileCompVal(t, c)).equals("derived"))
      return true;
    else
      return false;
  }

  /**
   * Check the reversibility of the given tile-component.
   *
   * @param t The index of the tile
   *
   * @param c The index of the component
   *
   * @return Whether or not the tile-component is reversible
     *
   */
  public boolean isReversible(int t, int c)
  {
    if (((String) getTileCompVal(t, c)).equals("reversible"))
      return true;
    else
      return false;
  }

  /**
   * Check the reversibility of the whole image.
   *
   * @return Whether or not the whole image is reversible
     *
   */
  public boolean isFullyReversible()
  {
    // The whole image is reversible if default specification is
    // rev and no tile default, component default and
    // tile-component value has been specificied
    if (((String) getDefault()).equals("reversible"))
    {
      for (int t = nTiles - 1; t >= 0; t--)
        for (int c = nComp - 1; c >= 0; c--)
          if (specValType[t][c] != SPEC_DEF)
            return false;
      return true;
    }

    return false;
  }

  /**
   * Check the irreversibility of the whole image.
   *
   * @return Whether or not the whole image is reversible
     *
   */
  public boolean isFullyNonReversible()
  {
    // The whole image is irreversible no tile-component is reversible
    for (int t = nTiles - 1; t >= 0; t--)
      for (int c = nComp - 1; c >= 0; c--)
        if (((String) getSpec(t, c)).equals("reversible"))
          return false;
    return true;
  }
}
