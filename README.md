# esys-mcp

An MCP server that lets Claude work with BMW E-Sys. The name is provisional.

Status: early development. Nothing is usable yet.

## Goal

- Edit a vehicle order (FA).
- Let E-Sys calculate the coding data (NCD) for it offline.
- Compare the results across many FA variants.

Later phases add reading FA, SVT and coding data from a vehicle.

## Scope

- F-series vehicles first.
- The tool calls E-Sys through its documented batch mode.
- It does not decrypt CAFD files or handle signatures.
- It does not flash ECUs (no TAL programming).
- It does not write to ECUs yet.

## Not included

E-Sys, PSdZData and tokens are not part of this project. Bring your own.

## Requirements

- Windows with E-Sys 3.30.1 (the only version tested) and PSdZData
- JDK 17 or later
- Maven

## Build

```
mvn verify
```

## Documentation

Design notes and findings are in `docs/` (Japanese).

## Warning

Coding can change or disable vehicle functions. Use this software at your own risk. It comes with no warranty.

BMW and E-Sys are trademarks of BMW AG. This project is not affiliated with or endorsed by BMW.

## License

Apache License 2.0. See [LICENSE](LICENSE).
