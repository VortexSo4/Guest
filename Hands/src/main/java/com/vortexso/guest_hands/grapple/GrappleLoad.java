package com.vortexso.guest_hands.grapple;

/**
 * The single durability rule for climbing and braking: the pickaxe pays for every block of distance
 * it carries the player, whether that distance is climbed, slid, or a fall it absorbs. Fractions
 * carry over so slow movement is not free and short hops are not over-charged.
 */
public final class GrappleLoad {
  private double owed;

  /** Adds carried distance and returns the whole durability points to apply now. */
  public int carry(double blocks, double durabilityPerBlock) {
    owed += Math.max(0.0, blocks) * durabilityPerBlock;
    int damage = (int) Math.floor(owed);
    owed -= damage;
    return damage;
  }
}
