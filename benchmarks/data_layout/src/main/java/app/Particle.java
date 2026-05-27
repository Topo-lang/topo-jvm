package app;

import java.util.Random;

public class Particle {
    public float x, y, z;
    public float vx, vy, vz;
    public float mass, charge;

    public Particle(Random rng) {
        x = rng.nextFloat();
        y = rng.nextFloat();
        z = rng.nextFloat();
        vx = rng.nextFloat();
        vy = rng.nextFloat();
        vz = rng.nextFloat();
        mass = rng.nextFloat();
        charge = rng.nextFloat();
    }
}
