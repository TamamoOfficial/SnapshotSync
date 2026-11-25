package com.snapsync.net;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server -> client, sent during the <b>configuration</b> phase: "here is what I have, and
 * where to get it".
 *
 * <p>This is the "authentication/check" leg of the flow. It intentionally rides the existing
 * Minecraft TCP connection instead of opening a second listener on the same port, which is
 * not possible: Minecraft already binds that port for this very connection.
 */
public record SnapshotOfferPayload(
        String snapshotId,
        String rootHash,
        int downloadPort,
        int manifestLength,
        byte[] manifestJson
) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<SnapshotOfferPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("snapsync", "offer"));

    public static final StreamCodec<ByteBuf, SnapshotOfferPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, SnapshotOfferPayload::snapshotId,
            ByteBufCodecs.STRING_UTF8, SnapshotOfferPayload::rootHash,
            ByteBufCodecs.VAR_INT, SnapshotOfferPayload::downloadPort,
            ByteBufCodecs.VAR_INT, SnapshotOfferPayload::manifestLength,
            ByteBufCodecs.BYTE_ARRAY, SnapshotOfferPayload::manifestJson,
            SnapshotOfferPayload::new
    );

    /** An "I have nothing to offer" answer, so the client never sits waiting. */
    public static SnapshotOfferPayload empty(int downloadPort) {
        return new SnapshotOfferPayload("", "", downloadPort, 0, new byte[0]);
    }

    public boolean isEmpty() {
        return snapshotId.isEmpty() || rootHash.isEmpty();
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
