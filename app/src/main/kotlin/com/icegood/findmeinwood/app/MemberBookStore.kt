package com.icegood.findmeinwood.app

import android.content.Context
import com.icegood.findmeinwood.core.model.LinkOffer
import com.icegood.findmeinwood.core.model.MemberId
import com.icegood.findmeinwood.core.model.NetworkId
import com.icegood.findmeinwood.core.model.RosterCodec
import com.icegood.findmeinwood.core.session.MemberBook
import com.icegood.findmeinwood.core.storage.SealedFiles
import java.io.File
import java.security.KeyPair

/** One member as the screens show it: from the master's list plus the member's own card. */
data class BookMember(
    val id: MemberId,
    val name: String?,
    val offers: List<LinkOffer>,
    val isMaster: Boolean,
    val isMe: Boolean,
    val removed: Boolean,
)

/** The running network's member list for the screens. */
data class BookView(val iAmMaster: Boolean, val masterKnown: Boolean, val members: List<BookMember>)

/**
 * Keeps each network's member list and member cards on disk, sealed like the profiles
 * (cards hold phone numbers): a phone knows every member even before hearing them.
 */
object MemberBookStore {
    private fun file(dir: File, net: NetworkId) =
        dir.resolve("book-${net.bytes.joinToString("") { "%02x".format(it) }}.bin")

    internal fun load(dir: File, net: NetworkId, me: KeyPair, masterId: MemberId?): MemberBook =
        MemberBook(net, me, masterId).apply {
            SealedFiles.read(file(dir, net))?.let { RosterCodec.decode(it) }?.let { merge(it) }
        }

    internal fun save(dir: File, net: NetworkId, book: MemberBook) =
        runCatching { SealedFiles.write(file(dir, net), RosterCodec.encode(book.toSync())) }

    fun load(ctx: Context, net: NetworkId, me: KeyPair, masterId: MemberId?) = load(ctx.filesDir, net, me, masterId)
    fun save(ctx: Context, net: NetworkId, book: MemberBook) = save(ctx.filesDir, net, book)

    fun delete(ctx: Context, net: NetworkId) = file(ctx.filesDir, net).delete()

    /** Master first, then by name; removed members last. */
    fun view(book: MemberBook): BookView {
        val listed = book.roster?.members.orEmpty()
        val ids = (listed.map { MemberId(it.id) } + book.cards().map { MemberId(it.id) } + book.myId).distinct()
        val members = ids.map { id ->
            val card = book.card(id)
            BookMember(
                id = id,
                name = card?.name ?: book.listedName(id),
                offers = card?.offers.orEmpty(),
                isMaster = id == book.masterId,
                isMe = id == book.myId,
                removed = book.isRemoved(id),
            )
        }.sortedWith(compareBy<BookMember>({ it.removed }, { !it.isMaster }, { it.name ?: "￿" }))
        return BookView(book.isMaster, book.masterId != null, members)
    }
}
