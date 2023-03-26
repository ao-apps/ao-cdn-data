/*
 * ao-cdn-data - API for accessing underlying content delivery network (CDN) data.
 * Copyright (C) 2023  AO Industries, Inc.
 *     support@aoindustries.com
 *     7262 Bull Pen Cir
 *     Mobile, AL 36695
 *
 * This file is part of ao-cdn-data.
 *
 * ao-cdn-data is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * ao-cdn-data is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with ao-cdn-data.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.aoapps.cdn.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests the {@link Resources} class.
 */
@SuppressWarnings("deprecation")
public class ResourcesTest {

  @Test
  public void testTotalHexChars() {
    assertEquals(Long.SIZE / Resources.BITS_PER_HEX_CHAR,
        Resources.HASH_CHARS + Resources.HASH_CHARS + Resources.RESOURCE_HASH_CHARS);
  }

  @Test
  @SuppressWarnings("ThrowableResultIgnored")
  public void testParseHexChar() {
    assertEquals(0, Resources.parseHexChar('0'));
    assertEquals(9, Resources.parseHexChar('9'));
    assertEquals(10, Resources.parseHexChar('a'));
    assertEquals(15, Resources.parseHexChar('f'));
    assertThrows(NumberFormatException.class, () -> Resources.parseHexChar((char) ('0' - 1)));
    assertThrows(NumberFormatException.class, () -> Resources.parseHexChar((char) ('9' + 1)));
    assertThrows(NumberFormatException.class, () -> Resources.parseHexChar((char) ('a' - 1)));
    assertThrows(NumberFormatException.class, () -> Resources.parseHexChar((char) ('f' + 1)));
    assertThrows(NumberFormatException.class, () -> Resources.parseHexChar('A'));
    assertThrows(NumberFormatException.class, () -> Resources.parseHexChar('F'));
    assertThrows(NumberFormatException.class, () -> Resources.parseHexChar((char) ('A' - 1)));
    assertThrows(NumberFormatException.class, () -> Resources.parseHexChar((char) ('F' + 1)));
  }

  @Test
  @SuppressWarnings("ThrowableResultIgnored")
  public void testParseHashName() {
    assertThrows("invalid null", NullPointerException.class, () -> Resources.parseHashName(null, 4));
    assertThrows("invalid empty", NumberFormatException.class, () -> Resources.parseHashName("", 4));
    assertThrows("invalid spaces", NumberFormatException.class, () -> Resources.parseHashName("    ", 4));
    assertEquals("valid zero prefix", 0x0123L, Resources.parseHashName("0123", 4));
    assertEquals("valid hex prefix", 0xF123L, Resources.parseHashName("f123", 4));
    assertThrows("invalid hex char", NumberFormatException.class, () -> Resources.parseHashName("g123", 4));
    assertThrows("invalid hex case", NumberFormatException.class, () -> Resources.parseHashName("F123", 4));
    assertThrows("invalid hex char", NumberFormatException.class, () -> Resources.parseHashName("G123", 4));
  }

  @Test
  @SuppressWarnings("ThrowableResultIgnored")
  public void testParseHash1Name() {
    assertThrows("invalid null", NullPointerException.class, () -> Resources.parseHash1Name(null));
    assertThrows("invalid empty", NumberFormatException.class, () -> Resources.parseHash1Name(""));
    assertThrows("invalid spaces", NumberFormatException.class, () -> Resources.parseHash1Name("    "));
    assertEquals("valid zero prefix", 0x0123000000000000L, Resources.parseHash1Name("0123"));
    assertEquals("valid hex prefix", 0xF123000000000000L, Resources.parseHash1Name("f123"));
    assertThrows("invalid hex char", NumberFormatException.class, () -> Resources.parseHash1Name("g123"));
    assertThrows("invalid hex case", NumberFormatException.class, () -> Resources.parseHash1Name("F123"));
    assertThrows("invalid hex char", NumberFormatException.class, () -> Resources.parseHash1Name("G123"));
  }

  @Test
  @SuppressWarnings("ThrowableResultIgnored")
  public void testParseHash2Name() {
    assertThrows("invalid null", NullPointerException.class, () -> Resources.parseHash2Name(null));
    assertThrows("invalid empty", NumberFormatException.class, () -> Resources.parseHash2Name(""));
    assertThrows("invalid spaces", NumberFormatException.class, () -> Resources.parseHash2Name("    "));
    assertEquals("valid zero prefix", 0x0000012300000000L, Resources.parseHash2Name("0123"));
    assertEquals("valid hex prefix", 0x0000F12300000000L, Resources.parseHash2Name("f123"));
    assertThrows("invalid hex char", NumberFormatException.class, () -> Resources.parseHash2Name("g123"));
    assertThrows("invalid hex case", NumberFormatException.class, () -> Resources.parseHash2Name("F123"));
    assertThrows("invalid hex char", NumberFormatException.class, () -> Resources.parseHash2Name("G123"));
  }

  @Test
  @SuppressWarnings("ThrowableResultIgnored")
  public void testParseResourceName() {
    assertThrows("invalid null", NullPointerException.class, () -> Resources.parseResourceName(null));
    assertThrows("invalid empty", NumberFormatException.class, () -> Resources.parseResourceName(""));
    assertThrows("invalid spaces", NumberFormatException.class, () -> Resources.parseResourceName("        "));
    assertEquals("valid zero prefix", 0x0000000001234567L, Resources.parseResourceName("01234567"));
    assertEquals("valid hex prefix", 0x00000000F1234567L, Resources.parseResourceName("f1234567"));
    assertThrows("invalid hex char", NumberFormatException.class, () -> Resources.parseResourceName("g1234567"));
    assertThrows("invalid hex case", NumberFormatException.class, () -> Resources.parseResourceName("F1234567"));
    assertThrows("invalid hex char", NumberFormatException.class, () -> Resources.parseResourceName("G1234567"));
  }

  @Test
  @SuppressWarnings("ThrowableResultIgnored")
  public void testiIsNewResource() {
    assertThrows("invalid null", NullPointerException.class, () -> Resources.isNewResource(null));
    assertTrue("valid zero prefix", Resources.isNewResource("01234567.new"));
    assertTrue("valid hex prefix", Resources.isNewResource("f1234567.new"));
    assertFalse("too short", Resources.isNewResource("0123456.new"));
    assertFalse("too long", Resources.isNewResource("012345678.new"));
    assertFalse("invalid hex case", Resources.isNewResource("F1234567.new"));
    assertTrue("valid hex char", Resources.isNewResource("f1234567.new"));
    assertFalse("wrong extension case", Resources.isNewResource("01234567.NEW"));
    assertFalse("missing extension", Resources.isNewResource("f1234567"));
  }
}
