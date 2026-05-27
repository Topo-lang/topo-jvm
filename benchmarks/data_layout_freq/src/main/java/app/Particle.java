package app;

public class Particle {
    public float x, y, z;
    public float vx, vy, vz;
    public float mass, charge;

    public Particle() {
        x = (float) Math.random();
        y = (float) Math.random();
        z = (float) Math.random();
        vx = (float) Math.random();
        vy = (float) Math.random();
        vz = (float) Math.random();
        mass = (float) Math.random();
        charge = (float) Math.random();
    }
}
