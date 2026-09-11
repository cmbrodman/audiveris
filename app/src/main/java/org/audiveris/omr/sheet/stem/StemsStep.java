//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                                        S t e m s S t e p                                       //
//                                                                                                //
//------------------------------------------------------------------------------------------------//
// <editor-fold defaultstate="collapsed" desc="hdr">
//
//  Copyright © Audiveris 2026. All rights reserved.
//
//  This program is free software: you can redistribute it and/or modify it under the terms of the
//  GNU Affero General Public License as published by the Free Software Foundation, either version
//  3 of the License, or (at your option) any later version.
//
//  This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
//  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
//  See the GNU Affero General Public License for more details.
//
//  You should have received a copy of the GNU Affero General Public License along with this
//  program.  If not, see <http://www.gnu.org/licenses/>.
//------------------------------------------------------------------------------------------------//
// </editor-fold>
package org.audiveris.omr.sheet.stem;

import org.audiveris.omr.sheet.Sheet;
import org.audiveris.omr.sheet.SystemInfo;
import org.audiveris.omr.sig.inter.BeamGroupInter;
import org.audiveris.omr.step.AbstractSystemStep;
import org.audiveris.omr.step.StepException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.geom.Line2D;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.audiveris.omr.math.LineUtil;
import org.audiveris.omr.sig.SIGraph;
import org.audiveris.omr.sig.inter.HeadInter;
import org.audiveris.omr.sig.inter.Inter;
import org.audiveris.omr.sig.inter.StemInter;
import org.audiveris.omr.sig.relation.HeadStemRelation;
import org.audiveris.omr.util.HorizontalSide;
import org.audiveris.omr.util.VerticalSide;

import org.audiveris.omr.constant.Constant;
import org.audiveris.omr.constant.ConstantSet;

import java.awt.Point;

/**
 * Class <code>StemsStep</code> implements <b>STEMS</b> step, which establishes all
 * possible relations between stems and note heads or beams.
 *
 * @author Hervé Bitteur
 */
public class StemsStep
        extends AbstractSystemStep<Void>
{
    //~ Static fields/initializers -----------------------------------------------------------------

    private static final Logger logger = LoggerFactory.getLogger(StemsStep.class);

    private static final Constants constants = new Constants();

    //~ Constructors -------------------------------------------------------------------------------

    /**
     * Creates a new StemsStep object.
     */
    public StemsStep ()
    {
    }

    //~ Methods ------------------------------------------------------------------------------------

    //----------//
    // doEpilog //
    //----------//
    @Override
    protected void doEpilog (Sheet sheet,
                            Void context)
        throws StepException
    {
        // Further beams processing
        for (SystemInfo system : sheet.getSystems()) {
            new StemsRetriever(system).finalizeBeams();

            // Compute all contextual grades (for better visual check)
            system.getSig().contextualize();
        }

        // Experimental stem-length normalization
        normalizeStemLengths(sheet);
    }

    //----------//
    // doProlog //
    //----------//
    /**
     * Make sure that beams are gathered into {@link BeamGroupInter} instances.
     * <p>
     * This is needed to stay compatible with .omr files built before 5.2 release
     *
     * @param sheet the sheet to check
     * @return null
     * @throws StepException if step got stopped
     */
    @Override
    @SuppressWarnings("deprecation")
    protected Void doProlog (Sheet sheet)
        throws StepException
    {
        for (SystemInfo system : sheet.getSystems()) {
            BeamGroupInter.checkSystemForOldBeamGroup(system);
        }

        return null;
    }

    //----------//
    // doSystem //
    //----------//
    @Override
    public void doSystem (SystemInfo system,
                          Void context)
        throws StepException
    {
        new StemsRetriever(system).process();
    }

    /**
     * Determine preferred stem direction for a paired-voice staff.
     *
     * -1 = stem up
     * +1 = stem down
     *  0 = no useful profile decision
     *
     * For the experimental paired-voice profile, heads vertically aligned
     * at approximately the same x position are compared.
     *
     * Upper head -> stem up
     * Lower head -> stem down
     */
    private int getPairedVoiceDirection (SIGraph sig,
                                        HeadInter anchor)
    {
        if (!constants.pairedVoiceStemProfile.isSet()) {
        return 0;
        }

        if (anchor.getStaff() == null) {
        return 0;
        }

        final Point anchorCenter = anchor.getCenter();

        if (anchorCenter == null) {
        return 0;
        }

        HeadInter nearestAbove = null;
        HeadInter nearestBelow = null;

        int aboveDistance = Integer.MAX_VALUE;
        int belowDistance = Integer.MAX_VALUE;

        //
        // Look for another head at nearly the same horizontal position
        // on the same physical staff.
        //
        for (Inter inter : sig.inters(HeadInter.class)) {
            final HeadInter other = (HeadInter) inter;

            if (other == anchor) {
                continue;
            }

            if (other.getStaff() != anchor.getStaff()) {
                continue;
            }

            final Point otherCenter = other.getCenter();

            if (otherCenter == null) {
                continue;
            }

            //
            // Restrict comparison to heads belonging to roughly
            // the same vertical note column.
            //
            final int xTolerance =
                    Math.max(
                            anchor.getBounds().width,
                            other.getBounds().width);

            if (Math.abs(otherCenter.x - anchorCenter.x) > xTolerance) {
                continue;
            }

            final int dy = otherCenter.y - anchorCenter.y;

            if ((dy < 0) && (-dy < aboveDistance)) {
                nearestAbove = other;
                aboveDistance = -dy;
            } else if ((dy > 0) && (dy < belowDistance)) {
                nearestBelow = other;
                belowDistance = dy;
            }
        }

        //
        // Remember: screen Y grows downward.
        //
        // Something below us means this is the upper voice.
        //
        if ((nearestBelow != null) && (nearestAbove == null)) {
        return -1; // Upper voice -> stem UP
        }

        //
        // Something above us means this is the lower voice.
        //
        if ((nearestAbove != null) && (nearestBelow == null)) {
            return 1; // Lower voice -> stem DOWN
        }

        //
        // If both exist, choose according to which neighboring layer
        // is closest.
        //
        if ((nearestAbove != null) && (nearestBelow != null)) {
            if (belowDistance < aboveDistance) {
                return -1; // More likely upper voice
            }

            if (aboveDistance < belowDistance) {
                return 1; // More likely lower voice
            }
        }

        return 0;
    }

        private void normalizeStemLengths (Sheet sheet)
    {
        final List<Integer> lengths = new ArrayList<>();

        // First pass: determine typical unbeamed stem length.
        for (SystemInfo system : sheet.getSystems()) {
            final SIGraph sig = system.getSig();

            for (Inter inter : sig.inters(StemInter.class)) {
                final StemInter stem = (StemInter) inter;

                if (stem.getHeads().isEmpty()) {
                    continue;
                }

                // Beamed stems are allowed to be longer.
                if (!stem.getBeams().isEmpty()) {
                    continue;
                }

                final Line2D median = stem.getMedian();
                final int length = (int) Math.rint(
                        Math.abs(median.getY2() - median.getY1()));

                if (length > 0) {
                    lengths.add(length);
                }
            }
        }

        if (lengths.isEmpty()) {
            return;
        }

        Collections.sort(lengths);

        final int typicalLength = lengths.get(lengths.size() / 2);

        logger.info(
                "EXPERIMENT stem normalization median: {} pixels",
                typicalLength);

        // Don't trim normal variation.
        final double maxNormalLength = typicalLength * 1.1;

        // Second pass: shorten only obvious outliers.
        for (SystemInfo system : sheet.getSystems()) {
            final SIGraph sig = system.getSig();

            for (Inter inter : sig.inters(StemInter.class)) {
                final StemInter stem = (StemInter) inter;

                final List<HeadInter> heads =
                        new ArrayList<>(stem.getHeads());

                if (heads.isEmpty()) {
                    continue;
                }

                if (!stem.getBeams().isEmpty()) {
                    continue;
                }

                final Line2D oldMedian = stem.getMedian();
                final double oldLength =
                        Math.abs(oldMedian.getY2() - oldMedian.getY1());

                if (oldLength <= maxNormalLength) {
                    continue;
                }

                // Find strongest attached head.
                HeadInter bestHead = null;
                double bestGrade = -1;

                for (HeadInter head : heads) {
                    final double grade = head.getBestGrade();

                    if (grade > bestGrade) {
                        bestGrade = grade;
                        bestHead = head;
                    }
                }

                if (bestHead == null) {
                    continue;
                }

                final HeadStemRelation rel =
                        (HeadStemRelation) sig.getRelation(
                                bestHead,
                                stem,
                                HeadStemRelation.class);

                if (rel == null) {
                    continue;
                }

                final HorizontalSide side = rel.getHeadSide();

                //
                // Existing geometry vote.
                //
                final int geometryDir;

                if (side == HorizontalSide.RIGHT) {
                    geometryDir = -1; // Stem up
                } else if (side == HorizontalSide.LEFT) {
                    geometryDir = 1; // Stem down
                } else {
                    continue;
                }

                //
                // Musical/profile vote.
                //
                final int profileDir =
                        getPairedVoiceDirection(sig, bestHead);

                final double bias =
                        constants.stemDirectionBias.getValue();

                final int dir;

                if (profileDir == 0) {

                    //
                    // Profile cannot determine the voice safely.
                    // Fall back to existing geometry.
                    //
                    dir = geometryDir;

                } else if (bias >= 1.0) {

                    //
                    // 100% means HARD RULE.
                    //
                    dir = profileDir;

                } else {

                    //
                    // Weighted vote.
                    //
                    // Negative = stem up
                    // Positive = stem down
                    //
                    final double vote =
                            (profileDir * bias)
                            + (geometryDir * (1.0 - bias));

                    if (vote < 0) {
                        dir = -1;
                    } else if (vote > 0) {
                        dir = 1;
                    } else {
                        dir = geometryDir;
                    }
                }

                final VerticalSide headEndSide =
                        (dir < 0) ? VerticalSide.BOTTOM : VerticalSide.TOP;

                final double headY =
                        bestHead.getStemReferencePoint(
                                side,
                                headEndSide).getY();

                final Line2D reliableLine = stem.getMedian();

                final Point2D headPoint =
                        LineUtil.intersectionAtY(
                                reliableLine,
                                headY);

                final double tailY =
                        headY + (dir * typicalLength);

                final Point2D tailPoint =
                        LineUtil.intersectionAtY(
                                reliableLine,
                                tailY);

                if (dir < 0) {
                    // Stem up: tail above, head below.
                    stem.setMedian(tailPoint, headPoint);
                } else {
                    // Stem down: head above, tail below.
                    stem.setMedian(headPoint, tailPoint);
                }

                logger.info(
                        "EXPERIMENT normalized stem {} from {} to {} pixels",
                        stem.getId(),
                        (int) Math.rint(oldLength),
                        typicalLength);
            }
        }
    }

    //-----------//
    // Constants //
    //-----------//
    private static class Constants
    extends ConstantSet
    {
    private final Constant.Boolean pairedVoiceStemProfile =
        new Constant.Boolean(
                true,
                "Use paired-voice stem direction profile");

    private final Constant.Ratio stemDirectionBias =
        new Constant.Ratio(
                1.00,
                "Stem direction profile bias (1.0 = hard rule)");
    }
}
