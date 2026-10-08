# Changelog

All notable changes to `barber-saas-workflow` are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [2.0.0] - 2026-10-08

MVP 2 (corte 2): first release of this repository to `main`, promoted from `develop` through `qa`
with `git cherry-pick -x` (norm 10–11).

User stories: code-corhuila/barber-saas-docs#7, code-corhuila/barber-saas-docs#59.

### Added

- **domain:** model the owner-onboarding saga and its steps
- **db:** version the workflow schema with its saga table
- **usecase:** orchestrate owner onboarding with its compensation
- **http:** call barbershop and identity-auth as saga participants
- **persistence:** store each saga in the workflow schema
- **http:** validate tokens on every saga route but the sign-up
- **http:** start owner onboarding and read a saga over HTTP
- **app:** wire the saga store, participants and startup recovery
- **deploy:** package the workflow and compose it with its runner
- **db:** accept PLAN_NOT_AVAILABLE as a saga failure reason
- **saga:** assign the plan the owner picked during onboarding

### Fixed

- **migrations:** restore the applied roles changeset byte for byte

### Documentation

- **readme:** explain what the workflow service is
- **readme:** explain how to start, where the data is and how to test
- **readme:** point the header to Barber Saas and barber-saas-docs

### Maintenance

- **app:** lay out the hexagonal workflow service
- **build:** build and test the workflow on every pull request
- **repo:** add the pull request template and board tracking
- **db:** migrate the workflow schema and check it rebuilds
- use the new repository name barber-saas-infra-postgres

[2.0.0]: https://github.com/code-corhuila/barber-saas-workflow/releases/tag/v2.0.0
