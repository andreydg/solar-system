package dev.andreydg.solarsystem.catalog;

import java.util.List;

public record CatalogPage(List<CatalogEvent> events, String nextCursor) {}
