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

import org.audiveris.omr.sheet.rhythm.Measure;
import org.audiveris.omr.sheet.rhythm.Voice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import org.audiveris.omr.sheet.Staff;

/**
 * Experimental read-only musical reasoning pass.
 *
 * This class examines Audiveris recognition results after rhythm processing.
 * It does not modify the score.
 */
public class ReasoningAnalyzer
{
    private static final Logger logger =
            LoggerFactory.getLogger(ReasoningAnalyzer.class);

    /**
     * When true, ordinary interior measures that are shorter than the
     * expected duration are reported just like overfull measures.
     *
     * Later this will become a user preference.
     */
    private static final boolean STRICT_UNDERFULL_CHECK = true;

    private static final boolean USE_STEM_DIRECTION_GROUPING = false;

    /** Page being analyzed. */
    private final Page page;

    private static class TemporaryVoiceOverlap
    {
        final Voice candidateVoice;
        final AbstractChordInter candidateChord;
        final Voice conflictingVoice;
        final AbstractChordInter conflictingChord;
        final int xDistance;

        TemporaryVoiceOverlap (Voice candidateVoice,
                            AbstractChordInter candidateChord,
                            Voice conflictingVoice,
                            AbstractChordInter conflictingChord,
                            int xDistance)
        {
            this.candidateVoice = candidateVoice;
            this.candidateChord = candidateChord;
            this.conflictingVoice = conflictingVoice;
            this.conflictingChord = conflictingChord;
            this.xDistance = xDistance;
        }
    }

    private List<TemporaryVoiceOverlap> findTemporaryVoiceOverlaps (Measure measure)
    {
        final List<TemporaryVoiceOverlap> matches = new ArrayList<>();

        for (Voice candidate : measure.getVoices()) {

            if (candidate.getChords().size() > 2) {
                continue;
            }

            final AbstractChordInter firstChord = candidate.getFirstChord();

            if (firstChord == null) {
                continue;
            }

            final Rational candidateStart = firstChord.getTimeOffset();

            if (candidateStart == null) {
                continue;
            }

            if (candidateStart.compareTo(Rational.ZERO) <= 0) {
                continue;
            }

            Voice conflictingVoice = null;
            AbstractChordInter conflictingChord = null;
            int bestXDistance = Integer.MAX_VALUE;

            for (Voice other : measure.getVoices()) {

                if (other == candidate) {
                    continue;
                }

                final AbstractChordInter otherFirst = other.getFirstChord();

                if (otherFirst == null) {
                    continue;
                }

                final Rational otherStart = otherFirst.getTimeOffset();

                if (otherStart == null) {
                    continue;
                }

                if (otherStart.compareTo(candidateStart) >= 0) {
                    continue;
                }

                for (AbstractChordInter chord : other.getChords()) {

                    final Rational chordStart = chord.getTimeOffset();

                    if ((chordStart != null)
                            && chordStart.equals(candidateStart)) {

                        final int xDistance = Math.abs(
                                chord.getCenter().x
                                        - firstChord.getCenter().x);

                        if (xDistance < bestXDistance) {
                            bestXDistance = xDistance;
                            conflictingVoice = other;
                            conflictingChord = chord;
                        }
                    }
                }
            }

            if (conflictingChord != null) {
                matches.add(
                        new TemporaryVoiceOverlap(
                                candidate,
                                firstChord,
                                conflictingVoice,
                                conflictingChord,
                                bestXDistance));
            }
        }

        return matches;
    }

    /**
     * Create an analyzer for one page.
     *
     * @param page page to analyze
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

            for (MeasureStack stack : system.getStacks()) {

                issueCount += analyzeStack(stack);
            }
        }

        logger.info(
                "========== REASONING ANALYSIS END: {} issue(s) found ==========",
                issueCount);
    }

    /**
     * Analyze one measure stack.
     *
     * @param stack measure stack
     * @return number of problems found
     */
    private int analyzeStack (MeasureStack stack)
    {
        final Rational expected = stack.getExpectedDuration();

        if (expected == null) {
            return 0;
        }

        logger.warn("");
        logger.warn(
                "==================== System {} Measure {} ====================",
                stack.getSystem().getId(),
                stack.getPageId());

        int issues = 0;

        // ------------------------------------------------------------
        // Existing overfull detection
        // ------------------------------------------------------------

        final Rational excess = stack.getExcess();

        if (excess != null) {

            logger.warn(
                    "REASONING: System {} Measure {} is OVERFULL"
                            + " - expected:{} excess:{}",
                    stack.getSystem().getId(),
                    stack.getPageId(),
                    expected,
                    excess);

            reportChords(stack);

            issues++;
        }

            

        // ------------------------------------------------------------
        // New strict underfull detection
        // ------------------------------------------------------------

        if (STRICT_UNDERFULL_CHECK && isStrictInteriorMeasure(stack)) {

            final Rational actual = stack.getActualDuration();

            if ((actual != null) && (actual.compareTo(expected) < 0)) {

                final Rational missing = expected.minus(actual);

                logger.warn(
                        "REASONING: System {} Measure {} is UNDERFULL"
                                + " - expected:{} actual:{} missing:{}",
                        stack.getSystem().getId(),
                        stack.getPageId(),
                        expected,
                        actual,
                        missing);

                reportChords(stack);

                issues++;
            }
        }
        issues += compareMeasures(stack);
        issues += reportTemporaryVoiceOverlaps(stack);

        // Experimental evidence scoring
        reportEvidenceScores(stack);

        // Observation-only stem-direction analysis
        reportStaffTexture(stack);

        // Observation-only staff/part structure check
        reportStaffMap(stack);

        reportReasoningVoiceMap(stack);

        return issues;
    }

            /**
     * Compare all vertically aligned part-measures in one measure stack.
     *
     * This is diagnostic only. It does not modify the score.
     *
     * @param stack measure stack
     * @return number of comparison anomalies found
     */
    private int compareMeasures (MeasureStack stack)
    {
        final Rational expected = stack.getExpectedDuration();

        if (expected == null) {
            return 0;
        }

        Rational referenceDuration = null;
        Integer referenceVoiceCount = null;

        boolean durationDisagreement = false;
        boolean voiceCountDisagreement = false;

        // First pass: determine whether the measures disagree.
        for (Measure measure : stack.getMeasures()) {

            final Rational duration = getMeasureDuration(measure);
            final int voiceCount = measure.getVoices().size();

            if (duration != null) {
                if (referenceDuration == null) {
                    referenceDuration = duration;
                } else if (!duration.equals(referenceDuration)) {
                    durationDisagreement = true;
                }
            }

            if (referenceVoiceCount == null) {
                referenceVoiceCount = voiceCount;
            } else if (voiceCount != referenceVoiceCount) {
                voiceCountDisagreement = true;
            }
        }

        // Nothing interesting to report.
        if (!durationDisagreement && !voiceCountDisagreement) {
            return 0;
        }

        logger.warn(
                "REASONING: System {} Measure {} has CROSS-PART DISAGREEMENT",
                stack.getSystem().getId(),
                stack.getPageId());

        int partIndex = 1;

        for (Measure measure : stack.getMeasures()) {

            final Rational duration = getMeasureDuration(measure);
            final int voiceCount = measure.getVoices().size();

            String rhythmStatus = "UNKNOWN";

            if (duration != null) {
                final int cmp = duration.compareTo(expected);

                if (cmp == 0) {
                    rhythmStatus = "OK";
                } else if (cmp < 0) {
                    rhythmStatus = "UNDERFULL";
                } else {
                    rhythmStatus = "OVERFULL";
                }
            }

            logger.warn(
                    "    Part {} duration:{} voices:{} status:{}",
                    partIndex,
                    duration,
                    voiceCount,
                    rhythmStatus);

            // Detailed voice timing for this suspicious measure
            reportVoiceTiming(measure, partIndex);

            partIndex++;
        }

        if (durationDisagreement) {
            logger.warn(
                    "    -> Duration disagreement between aligned measures");
        }

        if (voiceCountDisagreement) {
            logger.warn(
                    "    -> Voice-count disagreement between aligned measures");
        }

        return 1;
    }

    /**
     * Look for short-lived voices that begin after the start of the measure
     * and collide rhythmically with an already established voice.
     *
     * This is diagnostic only. It does not modify the score.
     *
     * @param stack measure stack
     * @return number of suspicious temporary voices found
     */
    private int reportTemporaryVoiceOverlaps (MeasureStack stack)
    {
        int issues = 0;
        int partIndex = 1;

        for (Measure measure : stack.getMeasures()) {

            final List<TemporaryVoiceOverlap> matches =
                    findTemporaryVoiceOverlaps(measure);

            for (TemporaryVoiceOverlap match : matches) {

                final Voice candidate = match.candidateVoice;
                final AbstractChordInter firstChord = match.candidateChord;
                final Voice conflictingVoice = match.conflictingVoice;
                final AbstractChordInter conflictingChord = match.conflictingChord;
                final int bestXDistance = match.xDistance;

                final Rational candidateStart =
                        firstChord.getTimeOffset();

                logger.warn(
                        "REASONING: System {} Measure {} Part {} has {}",
                        stack.getSystem().getId(),
                        stack.getPageId(),
                        partIndex,
                        firstChord.getDuration().equals(conflictingChord.getDuration())
                                ? "ALIGNED_TEMPORARY_VOICE"
                                : "CONFLICTING_DURATION_OVERLAP");

                logger.warn(
                        "    suspicious Voice {} starts:{} chords:{}",
                        candidate.getId(),
                        candidateStart,
                        candidate.getChords().size());

                logger.warn(
                        "    overlaps established Voice {} at:{} x-distance:{}",
                        conflictingVoice.getId(),
                        candidateStart,
                        bestXDistance);

                logger.warn(
                        "    candidate first chord id:{} x:{} duration:{}",
                        firstChord.getId(),
                        firstChord.getCenter().x,
                        firstChord.getDuration());

                logger.warn(
                        "    conflicting chord id:{} x:{} duration:{}",
                        conflictingChord.getId(),
                        conflictingChord.getCenter().x,
                        conflictingChord.getDuration());

                final boolean sameDuration =
                        firstChord.getDuration().equals(conflictingChord.getDuration());

                logger.warn(
                        "    duration relation:{}",
                        sameDuration ? "SAME_DURATION" : "DIFFERENT_DURATION");

                //
                // Print the complete suspicious voice so we can inspect it.
                //
                for (AbstractChordInter chord : candidate.getChords()) {

                    Rational start = chord.getTimeOffset();
                    Rational end = null;

                    try {
                        end = chord.getEndTime();
                    } catch (Exception ex) {
                        // Diagnostic only.
                    }

                    logger.warn(
                            "        candidate chord id:{} x:{}"
                                    + " start:{} duration:{} end:{}",
                            chord.getId(),
                            chord.getCenter().x,
                            start,
                            chord.getDuration(),
                            end);
                }

                issues++;
            }

            partIndex++;
        }

        return issues;
    }

    /**
     * Estimate the effective duration of one part-measure.
     *
     * For polyphonic music we do NOT add voice durations together.
     * Instead, we use the latest valid voice ending time.
     *
     * @param measure individual part-measure
     * @return effective duration, or null if it cannot be determined
     */
    private Rational getMeasureDuration (Measure measure)
    {
        Rational latest = null;

        for (Voice voice : measure.getVoices()) {

            // A measure rest represents the entire expected measure.
            if (voice.isMeasureRest()) {
                return measure.getStack().getExpectedDuration();
            }

            final Rational duration = voice.getDuration();

            if (duration == null) {
                continue;
            }

            if ((latest == null) || (duration.compareTo(latest) > 0)) {
                latest = duration;
            }
        }

        return latest;
    }

    /**
     * Report detailed timing information for every voice in one part-measure.
     *
     * This is diagnostic only. Nothing in the score is modified.
     *
     * @param measure   individual part-measure
     * @param partIndex displayed part number
     */
    private void reportVoiceTiming (Measure measure,
                                    int partIndex)
    {
        for (Voice voice : measure.getVoices()) {

            final Rational voiceDuration = voice.getDuration();
            final Rational termination = voice.getTermination();

            AbstractChordInter firstChord = voice.getFirstChord();
            AbstractChordInter lastChord = voice.getLastChord();

            Rational start = null;
            Rational end = null;

            if (firstChord != null) {
                start = firstChord.getTimeOffset();
            }

            if (lastChord != null) {
                try {
                    end = lastChord.getEndTime();
                } catch (Exception ex) {
                    // Leave end as null if Audiveris could not calculate it.
                }
            }

            logger.warn(
                    "        Voice {} start:{} end:{} duration:{} termination:{} chords:{}",
                    voice.getId(),
                    start,
                    end,
                    voiceDuration,
                    termination,
                    voice.getChords().size());

            // Print every chord belonging to this voice.
            for (AbstractChordInter chord : voice.getChords()) {

                Rational chordStart = chord.getTimeOffset();
                Rational chordEnd = null;

                try {
                    chordEnd = chord.getEndTime();
                } catch (Exception ex) {
                    // Leave null if timing is unavailable.
                }

                logger.warn(
                        "            chord id:{} x:{} start:{} duration:{} end:{}",
                        chord.getId(),
                        chord.getCenter().x,
                        chordStart,
                        chord.getDuration(),
                        chordEnd);
            }
        }
    }

    /**
     * Decide whether strict short-measure checking should apply.
     *
     * For now we exclude known Audiveris special-measure types.
     *
     * @param stack measure stack
     * @return true if strict checking should apply
     */
    private boolean isStrictInteriorMeasure (MeasureStack stack)
    {
        // Known special measure types
        if (stack.isImplicit()) {
            return false;
        }

        if (stack.isCautionary()) {
            return false;
        }

        if (stack.isFirstHalf()) {
            return false;
        }

        if (stack.isMultiRest()) {
            return false;
        }

        // For this first strict implementation, do not flag
        // the first measure of the page as underfull.
        if (stack.getPageId().equals("1")) {
            return false;
        }

        return true;
    }

    /**
     * Report all standard chords in a measure stack.
     */
    private void reportChords (MeasureStack stack)
    {
        for (AbstractChordInter chord : stack.getStandardChords()) {

            try {
                final Rational duration =
                        chord.isMeasureRest()
                                ? stack.getExpectedDuration()
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
    }

        /**
     * Calculate and report a simple reasoning evidence score for each
     * part-measure in the stack.
     *
     * This is diagnostic only. Nothing in the score is modified.
     *
     * @param stack measure stack
     */
    private void reportEvidenceScores (MeasureStack stack)
    {
        final Rational expected = stack.getExpectedDuration();

        if (expected == null) {
            return;
        }

        final boolean voiceCountDisagreement = hasVoiceCountDisagreement(stack);

        int partIndex = 1;

        for (Measure measure : stack.getMeasures()) {

            int score = 0;

            boolean underfull = false;
            boolean overfull = false;
            boolean alignedPartIsCorrect = false;
            boolean conflictingDurationOverlap = false;
            boolean unresolvedTiming = false;
            boolean missingContent = false;

            final Rational duration = getMeasureDuration(measure);

            //
            // Missing content
            //
            if (measure.getVoices().isEmpty()) {
                missingContent = true;
                score += 5;
            }

            //
            // Duration evidence
            //
            if (duration != null) {

                final int cmp = duration.compareTo(expected);

                if (cmp < 0) {
                    underfull = true;
                    score += 4;
                } else if (cmp > 0) {
                    overfull = true;
                    score += 4;
                }
            }

            //
            // Does another aligned part have the correct duration?
            //
            if (underfull || overfull || missingContent) {

                for (Measure other : stack.getMeasures()) {

                    if (other == measure) {
                        continue;
                    }

                    final Rational otherDuration = getMeasureDuration(other);

                    if ((otherDuration != null)
                            && otherDuration.equals(expected)) {

                        alignedPartIsCorrect = true;
                        score += 3;
                        break;
                    }
                }
            }

            //
            // Conflicting-duration overlap
            //
            conflictingDurationOverlap = hasConflictingDurationOverlap(measure);

            if (conflictingDurationOverlap) {
                score += 2;
            }

            //
            // Voice-count disagreement
            //
            if (voiceCountDisagreement) {
                score += 1;
            }

            //
            // Chords for which Audiveris could not establish timing.
            //
            unresolvedTiming = hasUnresolvedChordTiming(measure);

            if (unresolvedTiming) {
                score += 5;
            }

            //
            // Only print measures with some evidence.
            //
            if (score > 0) {

                logger.warn(
                        "REASONING SCORE: System {} Measure {} Part {}"
                                + " score:{} severity:{}",
                        stack.getSystem().getId(),
                        stack.getPageId(),
                        partIndex,
                        score,
                        getEvidenceSeverity(score));

                if (underfull) {
                    logger.warn("    +4 UNDERFULL_PART");
                }

                if (overfull) {
                    logger.warn("    +4 OVERFULL_PART");
                }

                if (alignedPartIsCorrect) {
                    logger.warn("    +3 ALIGNED_PART_HAS_CORRECT_DURATION");
                }

                if (conflictingDurationOverlap) {
                    logger.warn("    +2 CONFLICTING_DURATION_OVERLAP");
                }

                if (voiceCountDisagreement) {
                    logger.warn("    +1 VOICE_COUNT_DISAGREEMENT");
                }

                if (unresolvedTiming) {
                    logger.warn("    +5 UNRESOLVED_CHORD_TIMING");
                }

                if (missingContent) {
                    logger.warn("    +5 MISSING_PART_CONTENT");
                }
            }

            partIndex++;
        }
    }

        /**
     * Convert an evidence score into a simple severity category.
     *
     * @param score evidence score
     * @return severity label
     */
    private String getEvidenceSeverity (int score)
    {
        if (score >= 8) {
            return "STRONG";
        }

        if (score >= 5) {
            return "SUSPICIOUS";
        }

        if (score >= 3) {
            return "QUESTIONABLE";
        }

        return "INFORMATIONAL";
    }

        /**
     * Check whether aligned part-measures have different numbers of voices.
     *
     * @param stack measure stack
     * @return true if voice counts disagree
     */
    private boolean hasVoiceCountDisagreement (MeasureStack stack)
    {
        Integer referenceCount = null;

        for (Measure measure : stack.getMeasures()) {

            final int count = measure.getVoices().size();

            if (referenceCount == null) {
                referenceCount = count;
            } else if (count != referenceCount) {
                return true;
            }
        }

        return false;
    }

    /**
     * Check whether this measure contains at least one short-lived voice
     * that begins after the measure start and shares an onset with an
     * already established voice.
     *
     * The matching chord is selected using horizontal proximity, just as
     * in reportTemporaryVoiceOverlaps().
     *
     * @param measure part-measure
     * @return true if such a voice exists
     */
    private boolean hasTemporaryVoiceOverlap (Measure measure)
    {
        return !findTemporaryVoiceOverlaps(measure).isEmpty();
    }

    private boolean hasUnresolvedChordTiming (Measure measure)
    {
        for (Voice voice : measure.getVoices()) {
            for (AbstractChordInter chord : voice.getChords()) {
                if (chord.getTimeOffset() == null) {
                    return true;
                }
            }
        }

        return false;
    }

    private boolean hasConflictingDurationOverlap (Measure measure)
    {
        for (TemporaryVoiceOverlap match
                : findTemporaryVoiceOverlaps(measure)) {

            if (!match.candidateChord.getDuration().equals(
                    match.conflictingChord.getDuration())) {
                return true;
            }
        }

        return false;
    }

    private void reportStaffTexture (MeasureStack stack)
    {
        int measureIndex = 1;

        for (Measure measure : stack.getMeasures()) {

            int stemUp = 0;
            int stemDown = 0;
            int stemUnknown = 0;

            for (Voice voice : measure.getVoices()) {
                for (AbstractChordInter chord : voice.getChords()) {

                    if (chord.getStem() == null) {
                        stemUnknown++;
                        continue;
                    }

                    final int stemDir = chord.getStemDir();

                    if (stemDir < 0) {
                        stemUp++;
                    } else if (stemDir > 0) {
                        stemDown++;
                    } else {
                        stemUnknown++;
                    }
                }
            }

            logger.warn(
                    "STAFF TEXTURE: System {} Measure {} Part {}"
                            + " stem-up:{} stem-down:{} unknown:{}",
                    stack.getSystem().getId(),
                    stack.getPageId(),
                    measureIndex,
                    stemUp,
                    stemDown,
                    stemUnknown);

            measureIndex++;
        }
    }

    private int getExpectedVoiceId (MeasureStack stack,
                                    AbstractChordInter chord)
    {
    if (chord.getTopStaff() == null) {
    return 0;
    }

    final int staffIndex =
    stack.getSystem().getStaves().indexOf(chord.getTopStaff());

    final int stemDir = chord.getStemDir();

    if (stemDir == 0) {
    return 0;
    }

    if (staffIndex == 0) {
    return (stemDir < 0) ? 1 : 2;
    }

    if (staffIndex == 1) {
    return (stemDir < 0) ? 3 : 4;
    }

    return 0;
    }

    private void reportReasoningVoiceMap (MeasureStack stack)
    {
        int partIndex = 1;

        for (Measure measure : stack.getMeasures()) {

            for (Voice voice : measure.getVoices()) {

                for (AbstractChordInter chord : voice.getChords()) {

                    final int expectedVoiceId = getExpectedVoiceId(stack, chord);

                    // 0 means we cannot confidently apply the rule.
                    if (expectedVoiceId == 0) {
                        continue;
                    }

                    final int actualVoiceId = voice.getId();

                    final int staffIndex =
                            stack.getSystem().getStaves().indexOf(chord.getTopStaff());

                    final String staffName =
                            (staffIndex == 0) ? "UPPER" : "LOWER";

                    final String stemName =
                            (chord.getStemDir() < 0) ? "UP" : "DOWN";

                    logger.warn(
                            "REASONING VOICE MAP: Part {} Staff {}"
                                    + " chord:{} x:{} stem:{}"
                                    + " audiveris-voice:{} reasoning-voice:{}",
                            partIndex,
                            staffName,
                            chord.getId(),
                            chord.getCenter().x,
                            stemName,
                            actualVoiceId,
                            expectedVoiceId);
                    }
            }

            partIndex++;
        }
    }

    private void reportStaffMap (MeasureStack stack)
    {
        int partIndex = 1;

        for (Measure measure : stack.getMeasures()) {

            if (measure.getPart() != null) {

                logger.warn(
                        "STAFF MAP: Part {}",
                        partIndex);

                for (Staff staff : measure.getPart().getStaves()) {

                    final int systemStaffIndex =
                            stack.getSystem().getStaves().indexOf(staff);

                    logger.warn(
                            "    staff-id:{} index-in-part:{} index-in-system:{}",
                            staff.getId(),
                            staff.getIndexInPart(),
                            systemStaffIndex);
                }
            }

            partIndex++;
        }
    }
}