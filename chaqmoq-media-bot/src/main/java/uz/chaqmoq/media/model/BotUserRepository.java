package uz.chaqmoq.media.model;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface BotUserRepository extends JpaRepository<BotUser, Long> {

    @Query("SELECT COUNT(u) FROM BotUser u WHERE u.banned = false")
    long countActiveUsers();
}
