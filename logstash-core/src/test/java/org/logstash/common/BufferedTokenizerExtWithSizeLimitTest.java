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

package org.logstash.common;

import org.jruby.RubyArray;
import org.jruby.RubyString;
import org.jruby.runtime.ThreadContext;
import org.jruby.runtime.builtin.IRubyObject;
import org.junit.Before;
import org.junit.Test;
import org.logstash.RubyTestBase;
import org.logstash.RubyUtil;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.*;
import static org.logstash.RubyUtil.RUBY;

@SuppressWarnings("unchecked")
public final class BufferedTokenizerExtWithSizeLimitTest extends RubyTestBase {

    public static final int GB = 1024 * 1024 * 1024;
    private BufferedTokenizerExt sut;
    private ThreadContext context;

    @Before
    public void setUp() {
        initSUTWithSizeLimit(10);
    }

    private void initSUTWithSizeLimit(int sizeLimit) {
        sut = new BufferedTokenizerExt(RubyUtil.RUBY, RubyUtil.BUFFERED_TOKENIZER);
        context = RUBY.getCurrentContext();
        IRubyObject[] args = {RubyUtil.RUBY.newString("\n"), RubyUtil.RUBY.newFixnum(sizeLimit)};
        sut.init(context, args);
    }

    @Test
    public void givenTokenWithinSizeLimitWhenExtractedThenReturnTokens() {
        RubyArray<RubyString> tokens = (RubyArray<RubyString>) sut.extract(context, RubyUtil.RUBY.newString("foo\nbar\n"));

        assertEquals(List.of("foo", "bar"), tokens);
    }

    @Test
    public void givenTokenExceedingSizeLimitWhenExtractedThenThrowsAnError() {
        Exception thrownException = assertThrows(IllegalStateException.class, () -> {
            sut.extract(context, RubyUtil.RUBY.newString("this_is_longer_than_10\nkaboom"));
        });
        assertThat(thrownException.getMessage(), containsString("input buffer full"));
    }

    @Test
    public void givenExtractedThrownLimitErrorWhenFeedFreshDataThenReturnTokenStartingFromEndOfOffendingToken() {
        Exception thrownException = assertThrows(IllegalStateException.class, () -> {
            sut.extract(context, RubyUtil.RUBY.newString("this_is_longer_than_10\nkaboom"));
        });
        assertThat(thrownException.getMessage(), containsString("input buffer full"));

        RubyArray<RubyString> tokens = (RubyArray<RubyString>) sut.extract(context, RubyUtil.RUBY.newString("\nanother"));
        assertEquals("After buffer full error should resume from the end of line", List.of("kaboom"), tokens);
    }

    @Test
    public void givenExtractInvokedWithDifferentFramingAfterBufferFullErrorTWhenFeedFreshDataThenReturnTokenStartingFromEndOfOffendingToken() {
        sut.extract(context, RubyUtil.RUBY.newString("aaaa"));

        Exception thrownException = assertThrows(IllegalStateException.class, () -> {
            sut.extract(context, RubyUtil.RUBY.newString("aaaaaaa"));
        });
        assertThat(thrownException.getMessage(), containsString("input buffer full"));

        RubyArray<RubyString> tokens = (RubyArray<RubyString>) sut.extract(context, RubyUtil.RUBY.newString("aa\nbbbb\nccc"));
        assertEquals(List.of("bbbb"), tokens);
    }

    @Test
    public void giveMultipleSegmentsThatGeneratesMultipleBufferFullErrorsThenIsAbleToRecoverTokenization() {
        sut.extract(context, RubyUtil.RUBY.newString("aaaa"));

        //first buffer full on 13 "a" letters
        Exception thrownException = assertThrows(IllegalStateException.class, () -> {
            sut.extract(context, RubyUtil.RUBY.newString("aaaaaaa"));
        });
        assertThat(thrownException.getMessage(), containsString("input buffer full"));

        // second buffer full on 11 "b" letters
        Exception secondThrownException = assertThrows(IllegalStateException.class, () -> {
            sut.extract(context, RubyUtil.RUBY.newString("aa\nbbbbbbbbbbb\ncc"));
        });
        assertThat(secondThrownException.getMessage(), containsString("input buffer full"));

        // now should resemble processing on c and d
        RubyArray<RubyString> tokens = (RubyArray<RubyString>) sut.extract(context, RubyUtil.RUBY.newString("ccc\nddd\n"));
        assertEquals(List.of("ccccc", "ddd"), tokens);
    }

    @Test
    public void givenMaliciousInputExtractDoesntOverflow() {
        assertEquals("Xmx must equals to what's defined in the Gradle's javaTests task",
                12L * GB, Runtime.getRuntime().maxMemory());

        // re-init the tokenizer with big sizeLimit
        initSUTWithSizeLimit((int) (2L * GB) - 3);
        // Integer.MAX_VALUE is 2 * GB
        String bigFirstPiece = generateString("a", Integer.MAX_VALUE - 1024);
        sut.extract(context, RubyUtil.RUBY.newString(bigFirstPiece));

        // add another small fragment to trigger int overflow
        // sizeLimit is (2^32-1)-3 first segment length is (2^32-1) - 1024 second is 1024 +2
        // so the combined length of first and second is > sizeLimit and should throw an expection
        // but because of overflow it's negative and happens to be < sizeLimit
        Exception thrownException = assertThrows(IllegalStateException.class, () -> {
            sut.extract(context, RubyUtil.RUBY.newString(generateString("a", 1024 + 2)));
        });
        assertThat(thrownException.getMessage(), containsString("input buffer full"));
    }

    private String generateString(String fill, int size) {
        return fill.repeat(size);
    }
}