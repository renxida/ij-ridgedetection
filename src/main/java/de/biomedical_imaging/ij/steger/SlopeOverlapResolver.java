/*
 * #%L
 * Ridge Detection plugin for ImageJ
 * %%
 * Copyright (C) 2014 - 2015 Thorsten Wagner (ImageJ java plugin), 1996-1998 Carsten Steger (original C code), 1999 R. Balasubramanian (detect lines code to incorporate within GRASP)
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 2 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */

package de.biomedical_imaging.ij.steger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.biomedical_imaging.ij.steger.LinesUtil.contour_class;
import ij.IJ;

/**
 * Resolve overlap between lines by selecting the fragment with the closest
 * matching slope (which is really just trying to preserve straightness -
 * implying that a straight passover is more likely than two overlapping turns).
 *
 * @author Mark Hiner <hinerm@gmail.com>
 */
public class SlopeOverlapResolver extends AbstractOverlapResolver {

	// TODO add configuration for these
	// Sigma for deciding if two floats are "close enough"
	private static final float SIGMA = 2.0f;

	// Length from junction point to use in line segment calculation.
	// Smaller distances are more subject to variance, while larger distances
	// could introduce error due to actual curvature of line segments
	private static final int SLOPE_DIST = 7;

	@Override
	public Lines resolve(final Lines originalLines, final Junctions junctions,
		final boolean verbose)
	{
		if (verbose) IJ.log("### Overlap detection using Slope heuristic");

		final Set<Line> enclosedLines = new HashSet<Line>();
		final List<List<Line>> nWayIntersections = new ArrayList<List<Line>>();
		findOverlap(enclosedLines, nWayIntersections, junctions, verbose);

		final Map<Line, List<Line>> startIntersections =
			new HashMap<Line, List<Line>>();
		final Map<Line, List<Line>> endIntersections =
			new HashMap<Line, List<Line>>();

		buildIntersectionMaps(originalLines, enclosedLines, startIntersections,
			endIntersections, verbose);

		final List<List<Line>> lineMerges = new ArrayList<List<Line>>();

		// merge enclosed
		buildMergeList(lineMerges, enclosedLines, startIntersections,
			endIntersections, verbose);

		// merge n-way intersections
		buildMergeList(lineMerges, nWayIntersections, verbose);

		final Lines resolvedLines = buildResolvedList(originalLines, lineMerges,
			verbose);

		return resolvedLines;
	}

	/**
	 * Step 1: find enclosed lines (lines with a junction at both start and end
	 * point)
	 */
	private void findOverlap(final Set<Line> enclosed,
		final List<List<Line>> nWay, final Junctions junctions,
		final boolean verbose)
	{
		// Remember if a Junction is located at the start or end point of a Line
		Map<Line, Junction> startMatches = new HashMap<Line, Junction>();
		Map<Line, Junction> endMatches = new HashMap<Line, Junction>();

		// These enclosed lines will be treated as areas of overlap
		for (final Junction j : junctions) {
			// check if this junction sits on the start or end of either of its lines
			if (matchesStart(j, j.getLine1())) startMatches.put(j.getLine1(), j);
			if (matchesEnd(j, j.getLine1())) endMatches.put(j.getLine1(), j);

			if (matchesStart(j, j.getLine2())) startMatches.put(j.getLine2(), j);
			if (matchesEnd(j, j.getLine2())) endMatches.put(j.getLine2(), j);
		}

		if (verbose) {
			for (final Line l : startMatches.keySet()) {
				IJ.log("Found line " + l.getID() + " intersects with junction " +
					startMatches.get(l) + " at line start");
			}
			for (final Line l : endMatches.keySet()) {
				IJ.log("Found line " + l.getID() + " intersects with junction " +
					endMatches.get(l) + " at line end");
			}
		}

		enclosed.addAll(startMatches.keySet());
		enclosed.retainAll(endMatches.keySet());

		// tSections will contain lines that intersect at the same point as 2+ other
		// lines
		// none of which are enclosed
		final Set<Line> tSections = new HashSet<Line>(startMatches.keySet());
		tSections.addAll(endMatches.keySet());
		tSections.removeAll(enclosed);
		final List<List<Line>> lineSets = new ArrayList<List<Line>>();

		// Build our list of intersecting sets
		for (final Line l1 : tSections) {
			boolean found = false;

			// search all line sets until we find an intersection with
			// the current line
			for (int i = 0; i < lineSets.size() && !found; i++) {
				final List<Line> intersects = lineSets.get(i);

				for (int j = 0; j < intersects.size() && !found; j++) {
					final Line l2 = intersects.get(j);

					// NB: we do not need to take the extra step of ensuring all
					// lines intersect at the same point (instead of some intersecting at
					// the start
					// and some at the end, creating chains) because we have already
					// filtered out
					// the "enclosed" segments that would have arisen from that style of
					// overlap.
					if (intersects(l1, l2, SIGMA)) {
						found = true;
						intersects.add(l1);
					}
				}
			}

			// if no match, start next list
			if (!found) {
				final List<Line> lineList = new ArrayList<Line>();
				lineList.add(l1);
				lineSets.add(lineList);
			}
		}

		// populate nWay intersection list
		for (final List<Line> intersects : lineSets) {
			if (intersects.size() >= 3) nWay.add(intersects);
		}

		if (verbose) {
			for (final List<Line> isect : nWay) {
				final StringBuffer sb = new StringBuffer();
				sb.append("Found n-way intersection: ");
				for (final Line l : isect)
					sb.append(l.getID() + " ");
				IJ.log(sb.toString());
			}
		}

		// clean up enclosed lines.. any enclosed line that intersects with another
		// enclosed line at BOTH ends should be removed.

		boolean pruneEnclosed = true;

		while (pruneEnclosed) {
			pruneEnclosed = false;
			Line toRemove = null;

			for (final Line l1 : enclosed) {
				boolean foundStartMatch = false;
				boolean foundEndMatch = false;

				for (final Line l2 : enclosed) {
					if (l2 == l1) continue;
					else if (intersectsStart(l1, l2, SIGMA)) foundStartMatch = true;
					else if (intersectsEnd(l1, l2, SIGMA)) foundEndMatch = true;

					if (foundStartMatch && foundEndMatch) break;
				}

				// found a line to prune
				// remove it and restart
				if (foundStartMatch && foundEndMatch) {
					toRemove = l1;
					pruneEnclosed = true;
				}
			}

			if (toRemove != null) enclosed.remove(toRemove);
		}

		if (verbose) {
			for (final Line l : enclosed)
				IJ.log("Found enclosed line: " + l.getID());
		}
	}

	/**
	 * Step 2: for each enclosed line, we want to map to the sets of all other
	 * lines with one intersection at the start, and all lines with one
	 * intersection at the end.
	 */
	private void buildIntersectionMaps(final Lines lines,
		final Set<Line> enclosedLines,
		final Map<Line, List<Line>> startIntersections,
		final Map<Line, List<Line>> endIntersections, final boolean verbose)
	{
		for (final Line l1 : enclosedLines) {
			final List<Line> startIsect = new ArrayList<Line>();
			final List<Line> endIsect = new ArrayList<Line>();
			for (final Line l2 : lines) {
				if (l2 == l1) continue;
				else if (intersectsStart(l1, l2, SIGMA)) startIsect.add(l2);
				else if (intersectsEnd(l1, l2, SIGMA)) endIsect.add(l2);
			}
			startIntersections.put(l1, startIsect);
			endIntersections.put(l1, endIsect);
		}

		if (verbose) {
			for (final Line l1 : enclosedLines) {
				IJ.log("For enclosed line " + l1.getID() +
					" found intersecting lines: ");
				for (final Line l2 : startIntersections.get(l1)) {
					IJ.log("\tat start: " + l2.getID());
				}
				for (final Line l2 : endIntersections.get(l1)) {
					IJ.log("\tat end: " + l2.getID());
				}
			}
		}
	}

	/**
	 * Step 3a: With all mapped combinations determined, we determine the
	 * combinations that best preserve slope/straightness
	 */
	private void buildMergeList(final List<List<Line>> lineMerges,
		final Set<Line> enclosedLines,
		final Map<Line, List<Line>> startIntersections,
		final Map<Line, List<Line>> endIntersections, final boolean verbose)
	{
		for (final Line enclosed : enclosedLines) {

			// 1. first compute points of interest. We separate them by
			// count in case there are not even pairings
			final List<float[]> startPoints = new ArrayList<float[]>();
			final List<float[]> endPoints = new ArrayList<float[]>();

			final List<Line> startLines = startIntersections.get(enclosed);
			final List<Line> endLines = endIntersections.get(enclosed);

			final float[] enclosedStart = new float[] { enclosed.getXCoordinates()[0],
				enclosed.getYCoordinates()[0] };

			final float[] enclosedEnd = new float[] { enclosed
				.getXCoordinates()[enclosed.getNumber()-1], enclosed
					.getYCoordinates()[enclosed.getNumber()-1] };

			for (final Line l : startLines) {
				startPoints.add(getInterceptPoint(enclosedStart, l));
			}

			for (final Line l : endLines) {
				endPoints.add(getInterceptPoint(enclosedEnd, l));
			}


			// TODO minimize global error instead of greedily taking least error
			// 2. for each line in the small set, find the line in the large set
			// that would create the straightest merge.
			while (startLines.size() > 0 && endLines.size() > 0) {
				int startIndex = 0;
				int endIndex = 0;
				float minStraightness = Float.MAX_VALUE;
				final List<Line> toMerge = new ArrayList<Line>();

				for (int i = 0; i < startLines.size(); i++) {
					for (int j = 0; j < endLines.size(); j++) {
						final float straightness = straightCalc(startPoints.get(i), enclosedStart, enclosedEnd, endPoints.get(j));

						if (straightness < minStraightness) {
							startIndex = i;
							endIndex = j;
						}
					}
				}

				// Create the merge set
				toMerge.add(startLines.remove(startIndex));
				toMerge.add(enclosed);
				toMerge.add(endLines.remove(endIndex));

				// add it to the list of merges
				lineMerges.add(toMerge);

				// remove the selected points
				startPoints.remove(startIndex);
				endPoints.remove(endIndex);
			}

			// TODO this is an opportunity for configuration (could merge unmatched
			// lines with enclosed)
			// 3. Unmatched lines are treated as singletons
			final List<Line> unmatched = startLines.size() > 0 ? startLines : endLines;
			for (final Line line : unmatched) {
				final List<Line> toMerge = new ArrayList<Line>();
				toMerge.add(line);
				lineMerges.add(toMerge);
			}
		}

		// Clean up the merges
		// If the end of one merge == the start of another, those merges are joined
		// together
		int i = 0, j = 1;
		while (j < lineMerges.size()) {
			final List<Line> list1 = lineMerges.get(i);
			final List<Line> list2 = lineMerges.get(j);

			if (list1.get(0) == list2.get(list2.size() - 1)) {
				lineMerges.remove(i);
				list1.remove(0);
				list2.addAll(list1);
				i = 0;
				j = 1;
			}
			else if (list2.get(0) == list1.get(list1.size() - 1)) {
				lineMerges.remove(j);
				list2.remove(0);
				list1.addAll(list2);
				i = 0;
				j = 1;
			}
			else {
				j++;
				if (j == lineMerges.size()) {
					i++;
					j = i + 1;
				}
			}
		}

		if (verbose) {
			for (List<Line> merge : lineMerges) {
				StringBuilder sb = new StringBuilder();
				sb.append("Merging lines: ");

				for (final Line line : merge) {
					sb.append(line.getID());
					sb.append(" ");
				}
				IJ.log(sb.toString());
			}
		}
	}

	/**
	 * Step 3b: The process for determining merges from N-way merges is to find
	 * the point where each line intersects, then compute the straightness of each
	 * potential merge and take the straightest
	 */
	private void buildMergeList(List<List<Line>> lineMerges,
		List<List<Line>> nWayIntersections, boolean verbose)
	{
		for (final List<Line> iSection : nWayIntersections) {
			// 1. Check the first two lines to determine the junction
			final float[] junction = new float[2];

			final Line testLine = iSection.get(0);
			int index = 0;
			if (intersectsEnd(testLine, iSection.get(1), SIGMA)) index = testLine
				.getNumber() - 1;

			junction[0] = testLine.getXCoordinates()[index];
			junction[1] = testLine.getYCoordinates()[index];

			// TODO use global error minimization instead of being greedy
			// 2. Pair lines based on their straightness
			// Merge sets are added as pairs or unpaired singleton lines.
			while (iSection.size() > 1) {
				final List<Line> merge = new ArrayList<Line>();
				int idx1 = 0;
				int idx2 = 1;
				float minStraightness = Float.MAX_VALUE;

				// Find the merge with most straightness in our set of lines
				for (int i = 0; i < iSection.size(); i++) {
					final float[] icept = getInterceptPoint(junction, iSection.get(i));
					for (int j = i + 1; j < iSection.size(); j++) {
						final float[] jcept = getInterceptPoint(junction, iSection.get(j));

						final float curStraightness = straightCalc(icept, junction, jcept);
						if (curStraightness < minStraightness) {
							minStraightness = curStraightness;
							idx1 = i;
							idx2 = j;
						}
					}
				}

				// Add merged pair and remove from lists
				merge.add(iSection.get(idx1));
				merge.add(iSection.get(idx2));
				iSection.remove(idx1);
				// since we're removing from the same list, we need to decrement idx2 to
				// account
				// for removal of idx1
				idx2--;
				iSection.remove(idx2);

				lineMerges.add(merge);
			}

			if (iSection.size() == 1) {
				lineMerges.add(iSection);
			}
		}
	}

	/**
	 * Step 4: resolve merged line list
	 */
	private Lines buildResolvedList(final Lines originalLines,
		final List<List<Line>> lineMerges, final boolean verbose)
	{
		final Set<Line> finalLines = new HashSet<Line>(originalLines);

		for (final List<Line> toMerge : lineMerges) {
			// remove the individual, unmerged lines
			finalLines.removeAll(toMerge);

			int newSize = 0;
			int frame = 0;
			for (final Line l : toMerge) {
				frame = l.getFrame();
				newSize += l.getNumber();
			}

			// build and add the merged line
			Line merged = null;
			if (toMerge.size() == 1) {
				merged = toMerge.get(0);
			}
			else {
				merged = new Line();
				merged.angle = new float[newSize];
				merged.asymmetry = new float[newSize];
				merged.col = new float[newSize];
				merged.row = new float[newSize];
				merged.response = new float[newSize];
				merged.intensity = new float[newSize];
				merged.width_l = new float[newSize];
				merged.width_r = new float[newSize];
				merged.num = newSize;
				merged.setContourClass(contour_class.cont_closed);
				merged.setFrame(frame);

				// Get the enclosed line

				int pos = 0;
				for (int i = 0; i < toMerge.size(); i++) {
					final Line line = toMerge.get(i);
					Line adjacent = null;

					if (i == 0) adjacent = toMerge.get(i + 1);
					else adjacent = toMerge.get(i - 1);

					int num = line.getNumber();
					fillArray(line, adjacent, i == 0, merged.angle, pos, line.angle, num);
					fillArray(line, adjacent, i == 0, merged.asymmetry, pos,
						line.asymmetry, num);
					fillArray(line, adjacent, i == 0, merged.col, pos, line.col, num);
					fillArray(line, adjacent, i == 0, merged.row, pos, line.row, num);
					fillArray(line, adjacent, i == 0, merged.response, pos, line.response,
						num);
					fillArray(line, adjacent, i == 0, merged.intensity, pos,
						line.intensity, num);
					fillArray(line, adjacent, i == 0, merged.width_l, pos, line.width_l,
						num);
					fillArray(line, adjacent, i == 0, merged.width_r, pos, line.width_r,
						num);
					pos += num;
				}
			}

			finalLines.add(merged);
		}

		final Lines resolvedLines = new Lines(originalLines.getFrame());
		resolvedLines.addAll(finalLines);

		return resolvedLines;
	}

	/**
	 * Compute the striaghtness between the 3 given points. This is done by
	 * comparing distances: {@code ((p1 + p2) + (p2 + 3)) / (p3 + p1)} The closer
	 * the value is to 1, the straighter the line.
	 */
	private float straightCalc(final float[]... points) {
		float ideal = dist(points[0], points[points.length - 1]);
		float sum = 0;
		for (int i = 1; i < points.length; i++)
			sum += dist(points[i - 1], points[i]);

		return sum / ideal;
	}

	/**
	 * return the distance between 2 points
	 */
	private float dist(final float[] p1, final float[] p2) {
		return (float) Math.sqrt(Math.pow((p2[0] - p1[0]), 2) + Math.pow((p2[1] -
			p1[1]), 2));
	}

	/**
	 * Copy to the target at the given position. Use the source array if it is
	 * non-null. Otherwise fill with 0's
	 */
	private void fillArray(final Line line, final Line adjacent,
		final boolean adjacentIsNext, final float[] target, int pos,
		final float[] source, final int length)
	{
		if (source == null) Arrays.fill(target, pos, pos + length, 0f);
		else {
			// If the adjacent (reference) line is the next line in the sequence, then
			// we reverse the array if our current line's head is its intersection
			// point.
			// If the reference line is the previous line in the sequence, we reverse
			// the
			// array if our current line's tail is its intersection point.
			if ((adjacentIsNext && intersectsStart(line, adjacent, SIGMA)) ||
				(!adjacentIsNext && intersectsEnd(line, adjacent, SIGMA)))
			{
				// Reverse the source array
				for (int i = source.length - 1; i >= 0; i--) {
					target[pos++] = source[i];
				}
			}
			// oriented appropriately
			else System.arraycopy(source, 0, target, pos, length);
		}
	}

	/**
	 * Helper method to get the point at {@code SLOPE_DIST} positions away from
	 * the intercept point between the query line and given point.
	 */
	private float[] getInterceptPoint(final float[] p1, final Line query) {
		final float[] p2 = new float[2];
		int pos = Math.max(0, query.getNumber() - 1 - SLOPE_DIST);

		if (Math.abs(p1[0] - query.getXCoordinates()[0]) < SIGMA && Math.abs(p1[1] -
			query.getYCoordinates()[0]) < SIGMA)
		{
			pos = Math.min(SLOPE_DIST, query.getNumber() - 1);
		}

		p2[0] = query.getXCoordinates()[pos];
		p2[1] = query.getYCoordinates()[pos];

		return p2;
	}

	/**
	 * @return true iff the two specified lines intersect at their start or end
	 *         points
	 */
	private boolean intersects(final Line l1, final Line l2,
		final float threshold)
	{
		return intersectsStart(l1, l2, threshold) || intersectsEnd(l1, l2,
			threshold);
	}

	/**
	 * Helper method to determine if the query line intersects with the target at
	 * its start terminal
	 */
	private boolean intersectsStart(final Line target, final Line query,
		final float threshold)
	{
		final float[] tStart = getPoint(target, 0);
		final float[] qStart = getPoint(query, 0);
		final float[] qEnd = getPoint(query, query.getNumber() - 1);
		return intersects(tStart, qStart, threshold) || intersects(tStart, qEnd,
			threshold);
	}

	/**
	 * Helper method to determine if the query line intersects with the target at
	 * its end terminal
	 */
	private boolean intersectsEnd(final Line target, final Line query,
		final float threshold)
	{
		final float[] tEnd = getPoint(target, target.getNumber() - 1);
		final float[] qStart = getPoint(query, 0);
		final float[] qEnd = getPoint(query, query.getNumber() - 1);
		return intersects(tEnd, qStart, threshold) || intersects(tEnd, qEnd,
			threshold);
	}

	/**
	 * @return if the distance between two points is within the given threshold
	 */
	private boolean intersects(final float[] tEnd, final float[] qEnd,
		final float threshold)
	{
		return Math.abs(qEnd[0] - tEnd[0]) < threshold && Math.abs(qEnd[1] -
			tEnd[1]) < threshold;
	}

	/**
	 * Helper method to return the x and y coordinates of the point at the
	 * specified index of a given line.
	 */
	private float[] getPoint(final Line target, final int i) {
		float[] coords = new float[2];
		coords[0] = target.getXCoordinates()[i];
		coords[1] = target.getYCoordinates()[i];
		return coords;
	}

	/**
	 * Helper method to determine if a junction sits on the start point of a line
	 */
	private boolean matchesStart(final Junction junction, final Line line) {
		return line.getXCoordinates()[0] == junction.getX() && line
			.getYCoordinates()[0] == junction.getY();
	}

	/**
	 * Helper method to determine if a junction sits on the end point of a line
	 */
	private boolean matchesEnd(final Junction junction, final Line line) {
		int count = line.getNumber() - 1;
		return line.getXCoordinates()[count] == junction.getX() && line
			.getYCoordinates()[count] == junction.getY();
	}
}