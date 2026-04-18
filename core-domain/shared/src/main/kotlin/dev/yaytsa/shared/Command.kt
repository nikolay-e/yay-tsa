package dev.yaytsa.shared

/**
 * Marker interface for all commands across contexts.
 * Each context defines its own sealed subtype hierarchy.
 */
interface Command

/**
 * Marker interface for all queries across contexts.
 */
interface Query
