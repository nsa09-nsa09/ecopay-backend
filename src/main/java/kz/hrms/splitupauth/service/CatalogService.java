package kz.hrms.splitupauth.service;

import kz.hrms.splitupauth.dto.CategoryDto;
import kz.hrms.splitupauth.dto.ServiceDto;
import kz.hrms.splitupauth.dto.TariffPlanDto;
import kz.hrms.splitupauth.entity.ServiceEntity;
import kz.hrms.splitupauth.entity.TariffPlan;
import kz.hrms.splitupauth.exception.ResourceNotFoundException;
import kz.hrms.splitupauth.repository.CategoryRepository;
import kz.hrms.splitupauth.repository.ServiceRepository;
import kz.hrms.splitupauth.repository.TariffPlanRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CatalogService {

    private final CategoryRepository categoryRepository;
    private final ServiceRepository serviceRepository;
    private final TariffPlanRepository tariffPlanRepository;
    private final CatalogMapper catalogMapper;

    @Transactional(readOnly = true)
    public List<CategoryDto> getCategories() {
        return categoryRepository.findByIsActiveTrueOrderBySortOrderAscIdAsc()
                .stream()
                .map(catalogMapper::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ServiceDto> getServices(Long categoryId, String sort) {
        List<ServiceEntity> services = (categoryId != null)
                ? serviceRepository.findByCategoryIdAndIsActiveTrueOrderByIdAsc(categoryId)
                : serviceRepository.findByIsActiveTrueOrderByIdAsc();

        if (services.isEmpty()) {
            return List.of();
        }

        Map<Long, TariffStats> statsByService = loadTariffStats(services);

        List<ServiceDto> dtos = new ArrayList<>(services.size());
        for (ServiceEntity s : services) {
            TariffStats stats = statsByService.getOrDefault(s.getId(), TariffStats.EMPTY);
            dtos.add(catalogMapper.toDto(s, stats.minPricePerMember, stats.cheapestCurrency, stats.count));
        }

        sortServices(dtos, sort);
        return dtos;
    }

    @Transactional(readOnly = true)
    public ServiceDto getService(Long id) {
        ServiceEntity service = serviceRepository.findById(id)
                .filter(ServiceEntity::getIsActive)
                .orElseThrow(() -> new ResourceNotFoundException("Service not found"));

        Map<Long, TariffStats> statsByService = loadTariffStats(List.of(service));
        TariffStats stats = statsByService.getOrDefault(service.getId(), TariffStats.EMPTY);
        return catalogMapper.toDto(service, stats.minPricePerMember, stats.cheapestCurrency, stats.count);
    }

    @Transactional(readOnly = true)
    public List<TariffPlanDto> getTariffs(Long serviceId) {
        return tariffPlanRepository.findByServiceIdAndIsActiveTrueOrderByIdAsc(serviceId)
                .stream()
                .map(catalogMapper::toDto)
                .toList();
    }

    private Map<Long, TariffStats> loadTariffStats(List<ServiceEntity> services) {
        List<Long> ids = services.stream().map(ServiceEntity::getId).toList();
        List<TariffPlan> tariffs = tariffPlanRepository.findByServiceIdInAndIsActiveTrue(ids);

        Map<Long, TariffStats> result = new HashMap<>();
        for (TariffPlan t : tariffs) {
            if (t.getBasePriceTotal() == null || t.getMaxMembers() == null || t.getMaxMembers() <= 0) {
                // Treat as "no usable price"; still bump the tariff count below.
                BigDecimal perMember = null;
                result.compute(t.getService().getId(), (id, prev) -> bumpStats(prev, perMember, t.getCurrency()));
                continue;
            }
            BigDecimal perMember = t.getBasePriceTotal()
                    .divide(BigDecimal.valueOf(t.getMaxMembers()), 2, RoundingMode.HALF_UP);
            result.compute(t.getService().getId(),
                    (id, prev) -> bumpStats(prev, perMember, t.getCurrency()));
        }
        return result;
    }

    private TariffStats bumpStats(TariffStats prev, BigDecimal perMember, String currency) {
        if (prev == null) {
            return new TariffStats(perMember, perMember != null ? currency : null, 1);
        }
        int newCount = prev.count + 1;
        if (perMember == null) {
            return new TariffStats(prev.minPricePerMember, prev.cheapestCurrency, newCount);
        }
        if (prev.minPricePerMember == null
                || perMember.compareTo(prev.minPricePerMember) < 0) {
            return new TariffStats(perMember, currency, newCount);
        }
        return new TariffStats(prev.minPricePerMember, prev.cheapestCurrency, newCount);
    }

    private void sortServices(List<ServiceDto> dtos, String sort) {
        String key = sort == null ? "name_asc" : sort.toLowerCase(Locale.ROOT);
        Comparator<ServiceDto> byNameAsc = Comparator
                .comparing(ServiceDto::getName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(ServiceDto::getId);
        Comparator<ServiceDto> byNameDesc = Comparator
                .comparing(ServiceDto::getName, String.CASE_INSENSITIVE_ORDER).reversed()
                .thenComparing(ServiceDto::getId);

        // Per contract: services without an active tariff (and hence null min price)
        // go to the end regardless of asc/desc direction.
        Comparator<ServiceDto> byPriceAsc = Comparator
                .comparing(ServiceDto::getMinPricePerMember,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(ServiceDto::getId);
        Comparator<ServiceDto> byPriceDesc = Comparator
                .comparing(ServiceDto::getMinPricePerMember,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(ServiceDto::getId);

        // Newest = highest id, since we don't expose createdAt in ServiceDto and IDs are sequential.
        Comparator<ServiceDto> byNewest = Comparator
                .comparing(ServiceDto::getId, Comparator.reverseOrder());

        Comparator<ServiceDto> chosen = switch (key) {
            case "name_desc" -> byNameDesc;
            case "price_asc" -> byPriceAsc;
            case "price_desc" -> byPriceDesc;
            case "newest" -> byNewest;
            default -> byNameAsc;
        };
        dtos.sort(chosen);
    }

    private record TariffStats(BigDecimal minPricePerMember, String cheapestCurrency, int count) {
        static final TariffStats EMPTY = new TariffStats(null, null, 0);
    }
}
