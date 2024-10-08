/*
 * Licensed to Elasticsearch B.V. under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch B.V. licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *	http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */


package org.logstash.config.ir.compiler;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.logstash.ext.JrubyEventExtLibrary;

import java.util.Collection;
import java.util.List;

/**
 * Static utility methods that replace common blocks of generated code in the Java execution.
 */
public class Utils {
    private static final Logger logger = LogManager.getLogger(Utils.class);

    @SuppressWarnings({"unchecked", "rawtypes"})
    // has field1.compute(batchArg, flushArg, shutdownArg) passed as input
    public static void copyNonCancelledEvents(Collection<JrubyEventExtLibrary.RubyEvent> input, List output) {
        for (JrubyEventExtLibrary.RubyEvent e : input) {
            if (!(e.getEvent().isCancelled())) {
                output.add(e);
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void filterEvents(Collection<JrubyEventExtLibrary.RubyEvent> input, EventCondition filter,
                                    List fulfilled, List unfulfilled) {
        for (JrubyEventExtLibrary.RubyEvent e : input) {
            boolean isFulfilled;
            try {
                isFulfilled = filter.fulfilled(e);
            } catch (org.jruby.exceptions.TypeError | IllegalArgumentException | org.jruby.exceptions.ArgumentError ex) {
                // in case of error evaluation of an if condition, cancel the event
                e.getEvent().cancel();
                throw new ConditionalEvaluationError(ex, e.getEvent());
            }
            if (isFulfilled) {
                fulfilled.add(e);
            } else {
                unfulfilled.add(e);
            }
        }
    }

}
