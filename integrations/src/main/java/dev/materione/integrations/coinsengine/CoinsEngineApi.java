package dev.materione.integrations.coinsengine;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * CoinsEngine's economy API, reached by reflection (logic.md §18).
 *
 * <p>CoinsEngine's published API (ExcellentEconomy) is compiled for a newer Java than Materione's floor, so
 * Materione cannot compile against it. The API is a Bukkit service registered under the interface
 * {@code su.nightexpress.excellenteconomy.api.ExcellentEconomyAPI}; this class finds that service by its
 * class name and calls four of its methods ({@code getCurrency}, {@code getBalance}, {@code deposit},
 * {@code withdraw}), resolved once per registered API class. A CoinsEngine whose API does not have those
 * methods is reported as unavailable, with the reason, never as an error at load.
 */
final class CoinsEngineApi {

    /** The service interface CoinsEngine registers with Bukkit's service manager. */
    static final String SERVICE = "su.nightexpress.excellenteconomy.api.ExcellentEconomyAPI";

    private final Object api;
    private final Method getCurrency;
    private final Method getBalance;
    private final Method deposit;
    private final Method withdraw;

    private CoinsEngineApi(Object api, Method getCurrency, Method getBalance, Method deposit, Method withdraw) {
        this.api = api;
        this.getCurrency = getCurrency;
        this.getBalance = getBalance;
        this.deposit = deposit;
        this.withdraw = withdraw;
    }

    /** The registered API, or empty when CoinsEngine has not registered it. */
    static Optional<CoinsEngineApi> find() throws NoSuchMethodException {
        for (Class<?> service : Bukkit.getServicesManager().getKnownServices()) {
            if (!SERVICE.equals(service.getName())) {
                continue;
            }
            Object provider = Bukkit.getServicesManager().load(service);
            if (provider == null) {
                return Optional.empty();
            }
            Method getCurrency = service.getMethod("getCurrency", String.class);
            Class<?> currency = getCurrency.getReturnType();
            return Optional.of(new CoinsEngineApi(provider, getCurrency,
                    service.getMethod("getBalance", Player.class, currency),
                    service.getMethod("deposit", Player.class, currency, double.class),
                    service.getMethod("withdraw", Player.class, currency, double.class)));
        }
        return Optional.empty();
    }

    /** The service object itself: the same object while CoinsEngine's registration is unchanged. */
    Object identity() {
        return api;
    }

    /** The currency CoinsEngine defines under {@code id}, or null. */
    Object currency(String id) {
        return call(getCurrency, id);
    }

    double balance(Player player, Object currency) {
        return (Double) call(getBalance, player, currency);
    }

    boolean deposit(Player player, Object currency, double amount) {
        return (Boolean) call(deposit, player, currency, amount);
    }

    boolean withdraw(Player player, Object currency, double amount) {
        return (Boolean) call(withdraw, player, currency, amount);
    }

    private Object call(Method method, Object... arguments) {
        try {
            return method.invoke(api, arguments);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("CoinsEngine failed: " + cause, cause);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("CoinsEngine's API is not accessible: " + e.getMessage(), e);
        }
    }
}
