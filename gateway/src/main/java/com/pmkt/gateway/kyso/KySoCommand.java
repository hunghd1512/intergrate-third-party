package com.pmkt.gateway.kyso;

/**
 * Command object for the digital-signing use case.
 */
public record KySoCommand(
    String hopDongId,
    String documentName,
    String serialNumber
) {}
