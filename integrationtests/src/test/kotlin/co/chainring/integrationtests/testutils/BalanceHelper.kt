package co.chainring.integrationtests.testutils

import co.chainring.integrationtests.utils.ExpectedBalance
import co.chainring.integrationtests.utils.TestApiClient
import co.chainring.integrationtests.utils.assertBalances
import co.chainring.integrationtests.utils.assertBalancesMessageReceived
import org.http4k.websocket.WsClient

fun waitForBalance(apiClient: TestApiClient, wsClient: WsClient, expectedBalances: List<ExpectedBalance>) {
    wsClient.assertBalancesMessageReceived(expectedBalances)
    assertBalances(expectedBalances, apiClient.getBalances().balances)
}
