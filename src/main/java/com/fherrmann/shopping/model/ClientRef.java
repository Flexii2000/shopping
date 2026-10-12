package com.fherrmann.shopping.model;

import java.time.Instant;

/**
 * Eine Kennung, die eine App fuer einen neuen Eintrag vergeben hat, und der Eintrag,
 * der daraus wurde: derselbe, oder ein offener gleichen Namens, in dem er aufging.
 *
 * @param itemId der Eintrag auf der Liste
 * @param at     wann die Kennung ankam - alte raeumt der naechtliche Lauf weg
 */
public record ClientRef(String itemId, Instant at) {
}
