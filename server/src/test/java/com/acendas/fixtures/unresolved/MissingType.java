package com.acendas.fixtures.unresolved;

/**
 * Compiled normally so {@link HasUnresolvedSuper} can extend it, but
 * GraphExtractorsTest's AC-12 case copies only HasUnresolvedSuper.class into an
 * isolated class_dirs — so from SootUp's point of view this type is unresolvable
 * (becomes a phantom class).
 */
public class MissingType {
}
