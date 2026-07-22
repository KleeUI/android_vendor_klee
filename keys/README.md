# Release keys

Private signing material must not be committed. A private deployment may add:

- `keys.mk` for product certificate configuration;
- `BoardConfig.mk` for AVB configuration;
- the referenced private keys outside version control.
