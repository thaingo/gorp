/* 
 * Copyright (c) 2016, salesforce.com, inc.
 * All rights reserved.
 * Licensed under the BSD 3-Clause license. 
 * For full license text, see LICENSE.txt file in the repo root  or https://opensource.org/licenses/BSD-3-Clause
 */
package com.salesforce.gorp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.salesforce.gorp.autom.PolyMatcher;
import com.salesforce.gorp.jdkre.JDKRegexpExtractionCooker;
import com.salesforce.gorp.model.*;
import com.salesforce.gorp.util.RegexHelper;

/**
 * Processor built from a definition that is used to actually extract
 * information out of input lines.
 *<p>
 * Instances are fully thread-safe and may be used concurrently.
 */
public class Gorp
{
    /**
     * Multi-expression matcher that is capable of figuring out which extraction
     * rules, if any, matched. This is needed to know which actual extraction-based
     * regular expression to use for actual data extraction.
     */
    protected final PolyMatcher _matcher;

    protected final CookedExtraction[] _extractions;

    protected Gorp(PolyMatcher matcher, CookedExtraction[] extr) {
        _matcher = matcher;
        _extractions = extr;
    }

    public static Gorp construct(CookedDefinitions defs)
        throws DefinitionParseException
    {
        return construct(defs, JDKRegexpExtractionCooker.instance());
    }
    
    /**
     * Main factory method that will build {@link Gorp} out of fully
     * resolved {@link CookedDefinitions}.
     */
    public static Gorp construct(CookedDefinitions defs, ExtractionCooker cooker)
        throws DefinitionParseException
    {
        List<CookedExtraction> cookedExtr = new ArrayList<>();
        List<FlattenedExtraction> extractions = defs.getExtractions();
        List<String> automatonInputs = new ArrayList<>(extractions.size());
        // Use set to efficiently catch duplicate extractor names

        for (int i = 0, end = extractions.size(); i < end; ++i) {
            FlattenedExtraction ext = extractions.get(i);

            StringBuilder automatonInput = new StringBuilder();
            StringBuilder regexpInput = new StringBuilder();
            for (DefPiece part : ext) {
                _buildExtractor(automatonInput, regexpInput, cooker, part);
            }
    
            // last null -> no bindings from within extraction declaration
            automatonInputs.add(automatonInput.toString());

            final String regexpSource = regexpInput.toString();
            final int index = cookedExtr.size();
            try {
                cookedExtr.add(cooker.cook(index, regexpSource, ext));
            } catch (Exception e) { // should never occur. Probably does, so...
                ext.iterator().next()
                    .reportError("Internal problem: invalid regular expression segment, problem: %s", e.getMessage());
            }

        }
        // With that, can try constructing multi-matcher
        PolyMatcher poly = null;
        try {
            poly = PolyMatcher.create(automatonInputs);
        } catch (Exception e) {
            DefinitionParseException pe = DefinitionParseException.construct(
                    "Internal error: problem with PolyMatcher construction: "+ e.getMessage(),
                    null, 0);
            pe.initCause(e);
            throw pe;
        }
        return new Gorp(poly, cookedExtr.toArray(new CookedExtraction[cookedExtr.size()]));
    }

    private static void _buildExtractor(StringBuilder automatonInput, StringBuilder regexpInput,
            ExtractionCooker cooker, DefPiece part)
        throws DefinitionParseException
    {
        if (part instanceof LiteralPattern) {
            final String text = part.getText();
            try {
                RegexHelper.massageRegexpForAutomaton(text, automatonInput);
                cooker.appendPattern(text, regexpInput);
            } catch (Exception e) {
                part.reportError("Invalid pattern definition, problem (%s): %s",
                        e.getClass().getName(), e.getMessage());
            }
            return;
        }
        if (part instanceof LiteralText) {
            final String literal = part.getText();
            RegexHelper.quoteLiteralAsRegexp(literal, automatonInput);
            cooker.appendLiteral(literal, regexpInput);
            return;
        }
        if (part instanceof ExtractorExpression) {
            // not sure if we need to enclose it for Automaton, but shouldn't hurt
            automatonInput.append('(');
            cooker.appendStartExpression(regexpInput);
            // and for "regular" Regexp package, must add to get group
            ExtractorExpression extr = (ExtractorExpression) part;
            for (DefPiece p : extr.getParts()) {
                _buildExtractor(automatonInput, regexpInput, cooker, p);
            }
            automatonInput.append(')');
            cooker.appendFinishExpression(regexpInput);
            return;
        }
        part.reportError("Unrecognized DefPiece in FlattenedExtraction: %s", part.getClass().getName());
    }
    
    public List<CookedExtraction> getExtractions() {
        return Arrays.asList(_extractions);
    }

    public PolyMatcher getMatcher() {
        return _matcher;
    }

    /**
     * Match method that expects the first full match to work as expected,
     * evaluate extraction and return the result. If the first match
     * by multi-matcher fails for some reason (internal problem with
     * translations), a {@link ExtractionException} will be thrown.
     */
    public ExtractionResult extract(String input) throws ExtractionException {
        return extract(input, false);
    }

    /**
     * Match method that tries potential matches in order, returning first
     * that fully works. Should only be used if there is fear that sometimes
     * matchers are not properly translated; but if so, it is preferable to
     * get a lower-precedence match, or possible none at all.
     */
    public ExtractionResult extractSafe(String input) throws ExtractionException {
        return extract(input, true);
    }

    public ExtractionResult extract(String input, boolean allowFallbacks) throws ExtractionException
    {
        int[] matchIndexes = _matcher.match(input);
        if (matchIndexes.length == 0) {
            return null;
        }
        // First one ought to suffice, try that first
        int matchIndex = matchIndexes[0];
        CookedExtraction extr = _extractions[matchIndex];
        ExtractionResult result = extr.match(input);
        if (result != null) {
            return result;
        }
        // More than one? Should we throw an exception or play safe?
        if (!allowFallbacks) {
            throw new ExtractionException(input,
                    String.format("Internal error: high-level match for extraction #%d (%s) failed to match generated regexp: %s",
                            matchIndex, extr.getName(), extr.getRegexpDesc()));
        }
        for (int i = 1, end = matchIndexes.length; i < end; ++i) {
            result = _extractions[matchIndex].match(input);
            if (result != null) {
                return result;
            }
        }
        // nothing matches, despite initially seeming they would?
        return null;
    }
}
