/*
 * CalWatch
 * Copyright (C) 2014 by Dan Wallach
 * Home page: http://www.cs.rice.edu/~dwallach/calwatch/
 * Licensing: http://www.cs.rice.edu/~dwallach/calwatch/licensing.html
 */
package org.dwallach.calwatch

import android.os.SystemClock
import android.util.Log

import EDU.Washington.grad.gjb.cassowary.*

/**
 * new variant of event layout, this time with a full-blown constraint solver to make things
 * as pretty as possible
 */
object EventLayoutUniform {
    private const val TAG = "EventLayoutUniform"
    const val MAXLEVEL = 10000 // we'll go from 0 to MAXLEVEL, inclusive

    /**
     * Takes a list of calendar events and mutates their minLevel and maxLevel for calendar side-by-side
     * non-overlapping layout.

     * @param events list of events
     * *
     * @return true if it worked, false if it failed
     */
    fun go(events: List<EventWrapper>?): Boolean {
        Log.i(TAG, "Running uniform event layout with %d events".format(events?.size ?: 0))

        var i: Int
        var j: Int
        val nEvents: Int

        if (events == null) return true // degenerate case, in which we trivially succeed
        nEvents = events.size
        if (nEvents == 0) return true // degenerate case, in which we trivially succeed

        val overlapCounter = IntArray(nEvents)

        i = 1
        while (i < nEvents) {
            val e = events[i]

            // not sure this is necessary but it can't hurt
            e.minLevel = 0
            e.maxLevel = 0
            e.pathCache.set(null)
            i++
        }

        val nanoStart = SystemClock.elapsedRealtimeNanos()

        try {
            val solver = ClSimplexSolver()

            // TODO get rid of these nulls and directly initialize the arrays in a functional way
            val startLevels = arrayOfNulls<ClVariable>(nEvents)
            val sizes = arrayOfNulls<ClVariable>(nEvents)

            var sumSizes = ClLinearExpression(0.0)

            i = 0
            while (i < nEvents) {
                startLevels[i] = ClVariable("start" + i)
                sizes[i] = ClVariable("size" + i)

                // constraints: variables have to fit between 0 and max
                solver.addBounds(startLevels[i], 0.0, MAXLEVEL.toDouble())
                solver.addBounds(sizes[i], 0.0, MAXLEVEL.toDouble())

                // constraints: add them together and they're still constrained by MAXLEVEL
                val levelPlusSize = ClLinearExpression(startLevels[i]).plus(sizes[i])
                val liq = ClLinearInequality(levelPlusSize, CL.LEQ, ClLinearExpression(MAXLEVEL.toDouble()), ClStrength.required)
                solver.addConstraint(liq)

                sumSizes = sumSizes.plus(sizes[i])
                i++
            }

            // constraint: the sum of all the sizes is greater than the maximum it could ever be under the absolute best of cases
            // (this constraint's job is to force us out of degenerate cases when the solver might prefer zeros everywhere)
            val sumSizesEq = ClLinearInequality(sumSizes, CL.GEQ, ClLinearExpression((MAXLEVEL * nEvents).toDouble()), ClStrength.weak)
            solver.addConstraint(sumSizesEq)

            i = 0
            while (i < nEvents) {
                j = i + 1
                while (j < nEvents) {
                    if (events[i].overlaps(events[j])) {
                        overlapCounter[i]++
                        overlapCounter[j]++

                        // constraint: base level + its size < base level of next dependency
                        val levelPlusSize = ClLinearExpression(startLevels[i]).plus(sizes[i])
                        val liq = ClLinearInequality(levelPlusSize, CL.LEQ, startLevels[j], ClStrength.required)
                        solver.addConstraint(liq)

                        // weak constraint: constrained segments should have the same size (0.5x weight of other weak constraints)
                        // TODO introduce ratios here based on the time-duration of the event, so longer events are thinner than shorter ones
                        // -- doing this properly will change up the aesthetics a lot, so not something to be done casually.
                        val eqSize = ClLinearEquation(sizes[i], ClLinearExpression(sizes[j]), ClStrength.weak, 0.5)
                        solver.addConstraint(eqSize)
                    }
                    j++
                }
                i++

                // stronger constraint: each block size is greater than 1/N of the size, for overlap of N
                // (turns out that this didn't change the results, but removing it sped things up significantly)
                //                ClLinearInequality equalBlockSize = new ClLinearInequality(sizes[i], CL.GEQ, MAXLEVEL / (1+overlapCounter[i]), ClStrength.strong);
                //                solver.addConstraint(equalBlockSize);
            }

            // and... away we go!
            solver.solve()

            Log.v(TAG, "Event layout success.")

            i = 0
            while (i < nEvents) {
                val e = events[i]
                val start = startLevels[i]?.value()?.toInt() ?: 0
                val size = sizes[i]?.value()?.toInt() ?: 0

                e.minLevel = start
                e.maxLevel = start + size
                i++
            }
        } catch (e: ExCLInternalError) {
            Log.e(TAG, e.toString())
            return false
        } catch (e: ExCLRequiredFailure) {
            Log.e(TAG, e.toString())
            return false
        } catch (e: ExCLNonlinearExpression) {
            Log.e(TAG, e.toString())
            return false
        } finally {
            val nanoStop = SystemClock.elapsedRealtimeNanos()
            Log.v(TAG, "Event layout time: %.3f ms".format((nanoStop - nanoStart) / 1000000.0))
        }

        return true
    }
}
