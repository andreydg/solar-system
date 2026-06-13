package dev.andreydg.solarsystem.ephemeris;

public record Vector3Au(double x, double y, double z) {
    public Vector3Au minus(Vector3Au other) {
        return new Vector3Au(x - other.x, y - other.y, z - other.z);
    }

    public Vector3Au negate() {
        return new Vector3Au(-x, -y, -z);
    }

    public double magnitude() {
        return Math.sqrt(x * x + y * y + z * z);
    }

    public double angleBetweenDeg(Vector3Au other) {
        double denominator = magnitude() * other.magnitude();
        if (denominator == 0.0) {
            return 0.0;
        }

        double cosine = Math.max(-1.0, Math.min(1.0, dot(other) / denominator));
        return Math.toDegrees(Math.acos(cosine));
    }

    public double dot(Vector3Au other) {
        return x * other.x + y * other.y + z * other.z;
    }

    public double distanceTo(Vector3Au other) {
        double dx = x - other.x;
        double dy = y - other.y;
        double dz = z - other.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
