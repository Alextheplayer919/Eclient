package com.rubidiumclient.core.schem

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import org.cloudburstmc.protocol.bedrock.packet.UnknownPacket
import org.cloudburstmc.protocol.common.util.VarInts

/**
 * Hand-rolled ServerScriptDebugDrawer (packet ID 328) encoder for the classic
 * BOX shape wire path used by protocols 975 / 1001 / 2168 (= Bedrock 26.20–
 * 26.44; Bedrock_v2168 inherits DebugDrawerSerializer_v1001, whose BOX branch
 * dispatches through the v975 writeShape: v975-spread writeCommonShapeData +
 * payloadType varuint + raw vec3f boxBounds).
 *
 * Why hand-rolled raw bytes in an UnknownPacket instead of Cloudburst's own
 * DebugDrawerPacket: that class tree (DebugShape/DebugBox + serializers) is
 * bound to java.awt.Color, and java.awt does not exist on Android at all —
 * it fails at compile time, not just runtime. Every byte written here was
 * cross-checked against the actual serializer sources (v818/v859/v924/v975/
 * v1001 + Bedrock_v2168's inheritance) and the Bedrock packet-table data.
 *
 * Field order was extracted from DebugDrawerSerializer_v975.writeCommonShapeData
 * (which owns the common-data write for the whole 975–2168 chain):
 *   varuint64 id
 *   opt{type u8 ordinal}        BOX = 1
 *   opt{position vec3fLE}       box MIN corner
 *   opt{scale f32LE}            absent
 *   opt{rotation vec3fLE}       absent
 *   opt{totalTimeLeft f32LE}    seconds
 *   opt{maximumRenderDist f32LE} absent
 *   opt{color intLE AARRGGBB}   WRITE_COLOR = writeIntLE(Color.getRGB())
 *   opt{dimension varInt}       zigzag (0 overworld, 1 nether, 2 end)
 *   opt{attachedToEntityId varuint64} absent
 *   varuint payloadType         BOX = 3 (0 = delete marker when type absent)
 *   boxBounds vec3fLE raw       NOT option-tagged on this path
 *
 * Deletion: same id, every option absent (type absence = removal marker),
 * payloadType 0. Shapes also self-expire via totalTimeLeft, so a missed
 * delete is never fatal.
 */
object DebugDrawerBoxes {

    const val PACKET_ID      = 328
    const val MIN_PROTOCOL   = 975   // v975-layout wire path; older = fallback

    private const val SHAPE_BOX       = 1
    private const val PAYLOAD_BOX     = 3
    private const val PAYLOAD_DELETE  = 0

    private val BOUND = 1.0f + 1e-4f  // hair over 1.0 so shared faces z-fight-free appear flush

    /**
     * One packet containing `count` box shapes. `corners` is 3*count floats:
     * (x,y,z) of each box's MIN corner (i.e. the integer block coordinate).
     */
    fun addBoxesPacket(
        ids    : LongArray,
        corners: FloatArray,
        argb   : Int,
        ttlSeconds: Float,
        dimension : Int
    ): UnknownPacket {
        val b = Unpooled.buffer(ids.size * 44 + 8)
        VarInts.writeUnsignedInt(b, ids.size)
        for (i in ids.indices) {
            // common shape data
            VarInts.writeUnsignedLong(b, ids[i])
            b.writeByte(1); b.writeByte(SHAPE_BOX)             // type = BOX
            b.writeByte(1)                                     // position present
            b.writeFloatLE(corners[i * 3])
            b.writeFloatLE(corners[i * 3 + 1])
            b.writeFloatLE(corners[i * 3 + 2])
            b.writeByte(0)                                     // scale absent
            b.writeByte(0)                                     // rotation absent
            b.writeByte(1); b.writeFloatLE(ttlSeconds)         // totalTimeLeft
            b.writeByte(0)                                     // maxRenderDistance absent
            b.writeByte(1); b.writeIntLE(argb)                 // color (AARRGGBB)
            b.writeByte(1); VarInts.writeInt(b, dimension)     // dimension
            b.writeByte(0)                                     // attached entity absent
            // payload type + box-spec-ific data
            VarInts.writeUnsignedInt(b, PAYLOAD_BOX)
            b.writeFloatLE(BOUND); b.writeFloatLE(BOUND); b.writeFloatLE(BOUND)
        }
        return UnknownPacket().also { it.packetId = PACKET_ID; it.payload = b }
    }

    /** Delete the given shape ids client-side (type absent + zero payload). */
    fun removePacket(ids: LongArray): UnknownPacket {
        if (ids.isEmpty()) return UnknownPacket().also { it.packetId = PACKET_ID; it.payload = Unpooled.buffer(1).apply { VarInts.writeUnsignedInt(this, 0) } }
        val b = Unpooled.buffer(ids.size * 14 + 8)
        VarInts.writeUnsignedInt(b, ids.size)
        for (id in ids) {
            VarInts.writeUnsignedLong(b, id)
            b.writeByte(0)  // type absent      -> delete marker
            b.writeByte(0)  // position absent
            b.writeByte(0)  // scale absent
            b.writeByte(0)  // rotation absent
            b.writeByte(0)  // totalTimeLeft absent
            b.writeByte(0)  // maxRenderDistance absent
            b.writeByte(0)  // color absent
            b.writeByte(0)  // dimension absent
            b.writeByte(0)  // attached absent
            VarInts.writeUnsignedInt(b, PAYLOAD_DELETE)
        }
        return UnknownPacket().also { it.packetId = PACKET_ID; it.payload = b }
    }
}
