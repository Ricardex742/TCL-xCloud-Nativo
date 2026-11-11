package com.xcloud.tclnative

import com.xcloud.tclnative.catalog.*
import com.xcloud.tclnative.signaling.GameTitle
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CatalogAliasTest {
    private fun product()=StoreMetadata.galleryProduct(StoreMetadata.products(JSONObject(
        javaClass.getResource("/catalog/battlefield6-br-2026-11-10.json")!!.readText())).single())!!
    private fun game()=GameTitle("BATTLEFIELD","Battlefield","BASE00000001",supportedInputTypes=setOf("NativeTouch"))
    private fun gallery(products: List<GalleryProduct>)=OfficialGallery(OfficialGalleryKind.PURCHASED,products,true)
    private fun input(games: List<GameTitle>,products: List<GalleryProduct>,query: String="",tab: LibraryTab=LibraryTab.ALL,
                      favorites: Set<String> = emptySet())=CatalogInput(games,mapOf(OfficialGalleryKind.PURCHASED to gallery(products)),
        emptyMap(),null,emptySet(),tab,favorites,null,null,query)

    @Test fun actualOfficialBattlefield6MetadataDeclaresItsBattlefieldCloudBinding() {
        val product=product()
        assertEquals("9P2FF14JZLL3",product.productId);assertEquals("Battlefield™ 6",product.name)
        assertEquals("BATTLEFIELD",product.cloudTitleId);assertEquals(true,product.cloudSupported)
        val authenticated=game()
        val card=OfficialGalleryCards.select(gallery(listOf(product)),listOf(authenticated),emptyMap(),emptySet()).single()
        assertSame(authenticated,card.game)
        assertEquals("BATTLEFIELD",card.playableGame()!!.titleId)
        assertEquals("Battlefield™ 6",card.playableGame()!!.name)
        assertEquals(authenticated.supportedInputTypes,card.playableGame()!!.supportedInputTypes)
        assertEquals(authenticated.productId,card.playableGame()!!.productId)
    }
    @Test fun publicMetadataCannotInventAnAccountTitleOrBindByASimilarName() {
        assertNull(OfficialGalleryCards.resolve(product(),emptyList()))
        assertNull(OfficialGalleryCards.resolve(product(),listOf(game().copy(titleId="BATTLEFIELD2042",name="Battlefield™ 6"))))
        assertNull(OfficialGalleryCards.resolve(product().copy(cloudSupported=null),listOf(game())))
        assertNull(OfficialGalleryCards.resolve(product().copy(cloudSupported=false),listOf(game())))
        assertNull(OfficialGalleryCards.resolve(product(),listOf(game(),game().copy(titleId="battlefield"))))
    }
    @Test fun anAmbiguousDirectProductBindingStillNeedsAnAuthenticatedLookup() {
        val first=game().copy(productId=product().productId)
        val second=first.copy(titleId="OTHER-CLOUD")
        assertNull(OfficialGalleryCards.resolve(product(),listOf(first,second)))
        assertSame(first,OfficialGalleryCards.resolve(product(),listOf(first)))
        val explicit=OfficialGalleryCards.select(gallery(listOf(product())),listOf(first,second),
            mapOf(product().productId to first),emptySet()).single()
        assertSame(first,explicit.game)
    }
    @Test fun globalSearchUsesTheOfficialNameWithoutDuplicatingTheHubAndKeepsNativeIdentity() {
        for(query in listOf("battlefield 6","battlefield6","BATTLEFIELD™ 6")) {
            val result=CatalogPresentation.build(input(listOf(game()),listOf(product()),query))
            assertEquals(1,result.cards.size)
            assertEquals("Battlefield™ 6",result.cards.single().name)
            assertEquals("title:BATTLEFIELD",result.cards.single().key)
            assertEquals("BATTLEFIELD",result.cards.single().game!!.titleId)
        }
        assertEquals(1,CatalogPresentation.build(input(listOf(game()),listOf(product()))).cards.size)
    }
    @Test fun favoriteIdentityAndOldSearchTermsRemainValidAfterOfficialNamesArrive() {
        val result=CatalogPresentation.build(input(listOf(game()),listOf(product()),"battlefield 6",LibraryTab.FAVORITES,setOf("battlefield")))
        assertEquals("BATTLEFIELD",result.cards.single().game!!.titleId)
        assertEquals("Battlefield™ 6",result.cards.single().name)
        assertEquals(1,CatalogPresentation.build(input(listOf(game()),listOf(product()),"Battlefield")).cards.size)
    }
    @Test fun editionsWithTheSameCloudTargetRemainSeparateAndDoNotHideEachOtherInSearch() {
        val second=product().copy(productId="BBBBBBBBBBBB",name="Battlefield 6 — edição de demonstração")
        val result=CatalogPresentation.build(input(listOf(game()),listOf(product(),second),"Battlefield 6"))
        assertEquals(2,result.cards.size);assertEquals(2,result.cards.map { it.key }.distinct().size)
        assertTrue(result.cards.all { it.game?.titleId=="BATTLEFIELD" })
        assertEquals(setOf(product().productId,second.productId),result.cards.map { it.product!!.productId }.toSet())
    }
    @Test fun metadataHydrationDoesNotReplaceTheStableIdentityOfANativeCard() {
        val before=CatalogPresentation.build(input(listOf(game()),emptyList()))
        val after=CatalogPresentation.build(input(listOf(game()),listOf(product())))
        assertEquals(before.cards.single().key,after.cards.single().key)
        assertEquals(before.positions,after.positions)
        assertEquals("Battlefield",before.cards.single().name);assertEquals("Battlefield™ 6",after.cards.single().name)
    }
    @Test fun malformedPublicCloudIdsAreIgnoredAndMatchingIsCaseInsensitive() {
        for(value in listOf<Any>(123,true,"https://example.com/title","x\nsecret","x".repeat(257))) {
            val p=StoreMetadata.galleryProduct(JSONObject().put("StoreId","9P2FF14JZLL3").put("XCloudTitleId",value))!!
            assertNull(p.cloudTitleId)
        }
        val authenticated=game().copy(titleId="battlefield")
        assertSame(authenticated,OfficialGalleryCards.resolve(product(),listOf(authenticated)))
    }
}
