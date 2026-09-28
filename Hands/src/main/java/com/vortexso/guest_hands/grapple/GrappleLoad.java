package com.vortexso.guest_hands.grapple;

public final class GrappleLoad {
  private double owed;

  public int carry(double blocks, double durabilityPerBlock) {
    owed += Math.max(0.0, blocks) * durabilityPerBlock;
    int damage = (int) Math.floor(owed);
    owed -= damage;
    return damage;
  }
}
