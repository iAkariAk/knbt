package net.benwoodworth.knbt

public class NbtConfiguration internal constructor(
    public val variant: NbtVariant,
    public val compression: NbtCompression,
    public val compressionLevel: Int?,
    /**
     * Whether binary NBT strings are encoded and decoded using Java Modified UTF-8 instead of UTF-8.
     */
    public val mutf8: Boolean,
    override val encodeDefaults: Boolean,
    override val ignoreUnknownKeys: Boolean,
    override val classDiscriminator: String,
    override val nameRootClasses: Boolean,
) : NbtFormatConfiguration {
    override fun toString(): String =
        "NbtConfiguration(" +
                "variant=$variant" +
                ", compression=$compression" +
                ", compressionLevel=$compressionLevel" +
                ", mutf8=$mutf8" +
                ", encodeDefaults=$encodeDefaults" +
                ", ignoreUnknownKeys=$ignoreUnknownKeys" +
                ", classDiscriminator='$classDiscriminator'" +
                ", nameRootClasses=$nameRootClasses" +
                ")"
}
