package com.modsync.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server -> client handshake: tells the client where the download service actually lives.
 *
 * <p>This exists because the public port a player reaches is decided by whatever forwarding the
 * admin uses (FRP, NAT, a panel), so it cannot be derived on the client. The server states it.
 *
 * <p>Sent during the configuration phase, before mod negotiation, so the answer is available
 * even when the client is about to be rejected for missing mods.
 */
public record DownloadInfoPayload(String host, int port, int fileCount) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<DownloadInfoPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("modsync", "info"));

    public static final StreamCodec<FriendlyByteBuf, DownloadInfoPayload> STREAM_CODEC =
            StreamCodec.ofMember(DownloadInfoPayload::write, DownloadInfoPayload::read);

    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(host == null ? "" : host);
        buf.writeVarInt(port);
        buf.writeVarInt(fileCount);
    }

    public static DownloadInfoPayload read(FriendlyByteBuf buf) {
        String host = buf.readUtf();
        int port = buf.readVarInt();
        int count = buf.readVarInt();
        return new DownloadInfoPayload(host, port, count);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
