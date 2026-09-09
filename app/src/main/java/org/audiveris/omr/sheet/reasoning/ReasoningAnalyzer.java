//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                              R e a s o n i n g A n a l y z e r                               //
//                                                                                                //
//------------------------------------------------------------------------------------------------//

package org.audiveris.omr.sheet.reasoning;

import org.audiveris.omr.math.Rational;
import org.audiveris.omr.score.Page;
import org.audiveris.omr.sheet.SystemInfo;
import org.audiveris.omr.sheet.rhythm.MeasureStack;
import org.audiveris.omr.sig.inter.AbstractChordInter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Read-only experimental reasoning pass.
 * <p>
 * This class examines the result produced by the normal Audiveris rhythm
 * processing. It does not change any musical object.
 * </p>
 */
public class ReasoningAnalyzer
{
    private static final Logger logger = LoggerFactory.getLogger(ReasoningAnalyzer.class);

    /** Page being analyzed. */
    private final Page page;

    /**
     * Create a reasoning analyzer for one page.
     *
     * @param page the page to analyze
     */
    public ReasoningAnalyzer (Page page)
    {
        this.page = page;
    }

    /**
     * Analyze all systems and measure stacks on this page.
     */
    public void process ()
    {
        logger.info("========== REASONING ANALYSIS START ==========");

        int issueCount = 0;

        for (SystemInfo system : page.getSystems()) {
            logger.debug("Reasoning: examining system {}", system.getId());

            for (MeasureStack stack : system.getStacks()) {
                if (analyzeStack(stack)) {
                    issueCount++;
                }
            }
        }

        logger.info(
                "========== REASONING ANALYSIS END: {} issue(s) found ==========",
                issueCount);
    }

    /**
     * Analyze one vertical measure stack.
     *
     * @param stack measure stack
     * @return true if a possible issue was found
     */
    private boolean analyzeStack (MeasureStack stack)
    {
        final Rational expected = stack.getExpectedDuration();
        final Rational excess = stack.getExcess();

        // If Audiveris could not determine the expected duration,
        // there is nothing useful for this first experiment to check.
        if (expected == null) {
            return false;
        }

        // For version 1 we are interested only in measures that exceed
        // their expected duration.
        if (excess == null) {
            return false;
        }

        logger.warn(
                "REASONING: System {} Measure {} is OVERFULL - expected:{} excess:{}",
                stack.getSystem().getId(),
                stack.getPageId(),
                expected,
                excess);

        // Report the chords Audiveris placed in this measure stack.
        for (AbstractChordInter chord : stack.getStandardChords()) {
            try {
                final Rational duration = chord.isMeasureRest()
                        ? expected
                        : chord.getDuration();

                logger.warn(
                        "    chord id:{} x:{} duration:{}",
                        chord.getId(),
                        chord.getCenter().x,
                        duration);
            } catch (Exception ex) {
                logger.warn(
                        "    Could not examine chord {}",
                        chord,
                        ex);
            }
        }

        return true;
    }
}